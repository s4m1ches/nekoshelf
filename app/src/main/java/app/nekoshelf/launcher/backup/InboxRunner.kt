package app.nekoshelf.launcher.backup

import app.nekoshelf.launcher.backup.drive.Drive
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveException
import app.nekoshelf.launcher.backup.drive.RemoteFile
import app.nekoshelf.launcher.backup.drive.TreeFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** What one inbox check did. [error] is the short Russian text for the status line, null when all went fine. */
data class InboxResult(val received: Int, val skippedExisting: Int, val error: String?)

const val INBOX_ERROR_NO_ACCESS = "нет доступа к Диску — включите заново"
const val INBOX_ERROR_LOW_SPACE = "мало места"
private const val INBOX_ERROR_OTHER = "не удалось получить книгу с Диска"

/** App-created file in the `Nekoshelf` root: `{"ids":[…],"updated":<ms>}`, the seen set that survives a reader wipe. */
internal const val INBOX_SEEN_FILE = "inbox-seen.json"
private const val KIND_INBOX_SEEN = "inbox-seen"

/** A download is skipped (and retried later) when it would leave less free space than this. */
internal const val INBOX_MIN_FREE_BYTES = 200L * 1024 * 1024

private const val GOOGLE_NATIVE_PREFIX = "application/vnd.google-apps."
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/**
 * The files of a `Nekoshelf/books` [tree] the inbox should download: not Google-native (Docs,
 * shortcuts, folders — any `application/vnd.google-apps.*`), not hidden (name starting with `.`),
 * not the backup's own mirror copy (`appProperties.kind=book`), and not already [seen] (by Drive
 * file id — two siblings may share a name). Trashed files never reach here ([Drive.listTree]).
 */
fun inboxCandidates(tree: List<TreeFile>, seen: Set<String>): List<TreeFile> =
    tree.filter { t ->
        val f = t.file
        !f.mimeType.startsWith(GOOGLE_NATIVE_PREFIX) &&
            !f.name.startsWith(".") &&
            f.props[PROP_KIND] != KIND_BOOK &&
            f.id !in seen
    }.distinctBy { it.file.id }

/**
 * The Drive books inbox: files the owner dropped under `Nekoshelf/books` (any depth) land at the same
 * relative path under [homeDir], each once.
 *
 * - Finds `Nekoshelf/books` by name and creates nothing: no folder → nothing to do.
 * - The seen set is the local cache ([BackupStore.inboxSeen]) united with every `inbox-seen.json`
 *   in the `Nekoshelf` root; it is written back (new file, old ones deleted) when it changed.
 * - A download goes to a temp file beside the target and is moved into place without ever
 *   replacing a file; an existing path is skipped and marked seen. The file gets Drive's
 *   `modifiedTime` as its mtime. A failed download is not marked seen (retried next run); network
 *   and sign-in failures stop the run, others only that file.
 * - A file is skipped (not seen) when free space after it would drop below [INBOX_MIN_FREE_BYTES].
 *
 * Records the outcome in [store] (merged; the backup's own fields are untouched). Blocking: call
 * off the main thread.
 */
class InboxRunner(
    private val drive: Drive,
    private val store: BackupStore,
    private val homeDir: () -> File,
    private val freeBytes: (File) -> Long,
    private val clock: () -> Long,
) {
    /** Checks the inbox once. A run already in progress elsewhere makes this one do nothing. */
    fun run(): InboxResult {
        if (!RUNNING.compareAndSet(false, true)) return InboxResult(0, 0, null)
        try {
            return runLocked()
        } finally {
            RUNNING.set(false)
        }
    }

    private fun runLocked(): InboxResult {
        val start = store.load()
        val now = clock()
        val pass = Pass()
        val fatal = try {
            pass.check()
            null
        } catch (e: Exception) {
            inboxErrorText(e)
        }
        val error = fatal ?: pass.firstError ?: if (pass.lowSpace) INBOX_ERROR_LOW_SPACE else null
        store.update { cur ->
            // Signed out or another account since the start: this check means nothing for it.
            if (!cur.signedIn || cur.account != start.account) return@update cur
            cur.copy(inboxLastCheckAt = now, inboxReceived = pass.received, inboxError = error)
        }
        return InboxResult(pass.received, pass.skipped, error)
    }

    private inner class Pass {
        var received = 0
        var skipped = 0
        var lowSpace = false
        var firstError: String? = null
        private val seen = HashSet<String>()

        fun check() {
            val root = drive.findChild("root", DRIVE_ROOT)?.takeIf { it.isFolder() } ?: return
            val books = drive.findChild(root.id, DRIVE_BOOKS)?.takeIf { it.isFolder() } ?: return

            val seenFiles = drive.list(root.id).filter { it.name == INBOX_SEEN_FILE }
            val onDrive = HashSet<String>()
            seenFiles.forEach { onDrive += readSeen(it) }
            val local = store.inboxSeen()
            seen += local
            seen += onDrive
            if (seen != local) store.saveInboxSeen(seen)

            val home = homeDir()
            for (c in inboxCandidates(drive.listTree(books.id), seen)) receive(c, home)

            if (seen != onDrive) writeSeen(root.id, seenFiles)
        }

        private fun receive(c: TreeFile, home: File) {
            val target = resolveInside(home, c.relativePath)
            if (target == null) {
                markSeen(c.file.id) // an unsafe path never becomes safe
                return
            }
            if (target.exists()) {
                skipped++
                markSeen(c.file.id)
                return
            }
            if (freeBytes(home) - c.file.size < INBOX_MIN_FREE_BYTES) {
                lowSpace = true
                return
            }
            val dir = target.parentFile!!
            // Deepest first, so a failed download can remove exactly the folders it made.
            val madeDirs = generateSequence(dir) { it.parentFile }.takeWhile { !it.exists() }.toList()
            var part: File? = null
            var done = false
            try {
                dir.mkdirs()
                part = File.createTempFile(".inbox-", ".part", dir)
                drive.download(c.file.id, part)
                if (c.file.modifiedTime > 0) part.setLastModified(c.file.modifiedTime)
                try {
                    Files.move(part.toPath(), target.toPath()) // no REPLACE_EXISTING: never overwrites
                } catch (e: FileAlreadyExistsException) {
                    skipped++
                    markSeen(c.file.id)
                    return
                }
                if (c.file.modifiedTime > 0) target.setLastModified(c.file.modifiedTime)
                received++
                done = true
                markSeen(c.file.id)
            } catch (e: Exception) {
                if (isNetworkClass(e) || e is DriveAuthException) throw e
                if (firstError == null) firstError = backupErrorText(e, INBOX_ERROR_OTHER)
            } finally {
                part?.delete()
                if (!done) madeDirs.forEach { it.delete() } // delete() leaves a non-empty folder alone
            }
        }

        /** Persisted at once: a run killed later must not download this file again. */
        private fun markSeen(id: String) {
            if (seen.add(id)) store.saveInboxSeen(seen)
        }

        private fun readSeen(f: RemoteFile): Set<String> {
            val tmp = File.createTempFile("inbox-seen", ".json")
            try {
                drive.download(f.id, tmp)
                val ids = JSONObject(tmp.readText()).optJSONArray("ids") ?: return emptySet()
                return (0 until ids.length()).mapNotNull { ids.optString(it).ifEmpty { null } }.toSet()
            } catch (e: Exception) {
                if (isNetworkClass(e) || e is DriveAuthException) throw e
                if (e is JSONException || e is DriveException) return emptySet() // unreadable: replaced on write
                throw e
            } finally {
                tmp.delete()
            }
        }

        /** Uploads the merged set as a new file, then removes the copies it replaces. */
        private fun writeSeen(rootId: String, old: List<RemoteFile>) {
            val tmp = File.createTempFile("inbox-seen", ".json")
            try {
                val json = JSONObject().put("ids", JSONArray(seen.sorted())).put("updated", clock())
                tmp.writeText(json.toString())
                drive.upload(rootId, INBOX_SEEN_FILE, tmp, mapOf(PROP_KIND to KIND_INBOX_SEEN), null)
            } finally {
                tmp.delete()
            }
            // A copy left behind (delete failed) is merged in next time: the set only grows.
            for (f in old) runCatching { drive.delete(f.id) }
        }
    }

    private fun RemoteFile.isFolder() = mimeType == FOLDER_MIME

    private companion object {
        /** Process-wide: the wake work, a backup run and the button must not download the same file twice. */
        val RUNNING = AtomicBoolean(false)
    }
}

/** Lost readonly access (token rejected, or 403 from listing) asks the owner to turn the checkbox on again. */
internal fun inboxErrorText(e: Throwable): String = when {
    e is DriveAuthException -> INBOX_ERROR_NO_ACCESS
    e is DriveException && e.code == 403 -> INBOX_ERROR_NO_ACCESS
    else -> backupErrorText(e, INBOX_ERROR_OTHER)
}
