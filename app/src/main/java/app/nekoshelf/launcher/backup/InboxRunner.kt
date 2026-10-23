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
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** What one inbox check did. [error] is the short Russian text for the status line, null when all went fine. */
data class InboxResult(val received: Int, val skippedExisting: Int, val error: String?)

const val INBOX_ERROR_NO_ACCESS = "нет доступа к Диску — включите заново"
const val INBOX_ERROR_LOW_SPACE = "мало места"
private const val INBOX_ERROR_OTHER = "не удалось получить книгу с Диска"
const val INBOX_ERROR_NO_ROOT = "на Диске нет папки Nekoshelf"
const val INBOX_ERROR_MANY_BOOKS = "в папке Nekoshelf несколько папок books"

/** A download is skipped (and retried later) when it would leave less free space than this. */
internal const val INBOX_MIN_FREE_BYTES = 200L * 1024 * 1024

/** Download temp files: `.inbox-<random>.part` (DriveRest adds its own `.part` while writing). */
private const val PART_PREFIX = ".inbox-"
private const val PART_SUFFIX = ".part"
private const val STALE_PART_MS = 24L * 60 * 60 * 1000

internal const val GOOGLE_NATIVE_PREFIX = "application/vnd.google-apps."

/**
 * The files of a `Nekoshelf/books` [tree] the inbox should download: not Google-native (Docs,
 * shortcuts, folders — any `application/vnd.google-apps.*`), not hidden (the name or any folder
 * on the way starting with `.`),
 * not the backup's own mirror copy (`appProperties.kind=book`), and not already [seen] (by Drive
 * file id — two siblings may share a name). Trashed files never reach here ([Drive.listTree]).
 */
fun inboxCandidates(tree: List<TreeFile>, seen: Set<String>): List<TreeFile> =
    tree.filter { t ->
        val f = t.file
        !f.mimeType.startsWith(GOOGLE_NATIVE_PREFIX) &&
            !f.name.startsWith(".") &&
            t.relativePath.split('/').none { it.startsWith(".") } &&
            f.props[PROP_KIND] != KIND_BOOK &&
            f.id !in seen
    }.distinctBy { it.file.id }

/**
 * The Drive books inbox: files the owner dropped under `Nekoshelf/books` (any depth) land at the same
 * relative path under [homeDir], each once.
 *
 * - Finds `Nekoshelf/books` by name and creates nothing. Only folders count. Several `Nekoshelf`
 *   folders in My Drive: the one holding the backup's KOReader snapshots or an app-made
 *   `inbox-seen.json` is used; none or more than one of those → an error. No `Nekoshelf` → nothing
 *   to do (an error once a backup has succeeded); no `books` → nothing to do; two `books` → an error.
 * - The seen set is the local cache ([BackupStore.inboxSeen]) united with every app-made
 *   (`kind=inbox-seen`) `inbox-seen.json` in the `Nekoshelf` root. Reading a copy must work: any
 *   Drive or I/O failure stops the run before anything is downloaded (a wiped reader would
 *   otherwise pull every book back), except a copy gone since the listing (404), which is skipped.
 *   It is written back (new file; the copies read or found corrupt are then deleted) when it changed.
 * - Stale download temp files (`.inbox-*.part*`, older than a day) are removed from the folders
 *   this run writes to.
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
        val pass = Pass(hadBackup = start.lastSuccessAt != null)
        var authLost = false
        val fatal = try {
            pass.check()
            null
        } catch (e: Exception) {
            authLost = e is DriveAuthException
            inboxErrorText(e)
        }
        val error = fatal ?: pass.firstError ?: if (pass.lowSpace) INBOX_ERROR_LOW_SPACE else null
        store.update { cur ->
            // Signed out or another account since the start: this check means nothing for it.
            if (!cur.signedIn || cur.account != start.account) return@update cur
            cur.copy(
                inboxLastCheckAt = now,
                inboxReceived = pass.received,
                inboxError = error,
                inboxNeedsReauth = authLost, // a run that got through proves access is back
            )
        }
        return InboxResult(pass.received, pass.skipped, error)
    }

    private inner class Pass(private val hadBackup: Boolean) {
        var received = 0
        var skipped = 0
        var lowSpace = false
        var firstError: String? = null
        private val seen = HashSet<String>()

        fun check() {
            val root = backupRoot() ?: return
            val books = drive.findChildren(root.id, DRIVE_BOOKS).filter { it.isFolder() }
            if (books.size > 1) throw InboxFailure(INBOX_ERROR_MANY_BOOKS)
            val booksId = books.singleOrNull()?.id ?: return

            val onDrive = HashSet<String>()
            val replaceable = ArrayList<RemoteFile>() // read fine or corrupt: superseded by the next write
            var corrupt = false
            for (f in seenCopies(root.id)) {
                when (val r = readSeen(f)) {
                    is SeenCopy.Ids -> { onDrive += r.ids; replaceable += f }
                    SeenCopy.Corrupt -> { corrupt = true; replaceable += f }
                    SeenCopy.Gone -> Unit // deleted since the listing: not ours to delete again
                }
            }
            val local = store.inboxSeen()
            seen += local
            seen += onDrive
            if (seen != local) store.saveInboxSeen(seen)

            val home = homeDir()
            val candidates = inboxCandidates(drive.listTree(booksId), seen)
            sweepStaleParts(candidates.mapNotNull { resolveInside(home, it.relativePath)?.parentFile }.toSet())
            for (c in candidates) receive(c, home)

            if (seen != onDrive || corrupt || replaceable.size > 1) writeSeen(root.id, replaceable)
        }

        /** The backup's `Nekoshelf` folder ([DriveRoots.resolve]); none is an error once a backup has succeeded. */
        private fun backupRoot(): RemoteFile? =
            DriveRoots.resolve(drive) ?: if (hadBackup) throw InboxFailure(INBOX_ERROR_NO_ROOT) else null

        private fun seenCopies(rootId: String): List<RemoteFile> = DriveRoots.seenCopies(drive, rootId)

        /** Leftovers of runs killed mid-download; today's are left alone. */
        private fun sweepStaleParts(dirs: Set<File>) {
            val cutoff = System.currentTimeMillis() - STALE_PART_MS
            for (dir in dirs) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(PART_PREFIX) && PART_SUFFIX in f.name && f.lastModified() < cutoff) f.delete()
                }
            }
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
                part = File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
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

        /**
         * Any failure other than corrupt content or a 404 propagates: the run must stop. Content
         * that does not parse counts as corrupt only when all of the file arrived (a cut-off
         * transfer is a failure, not a reason to overwrite the record).
         */
        private fun readSeen(f: RemoteFile): SeenCopy {
            val tmp = File.createTempFile("inbox-seen", ".json")
            try {
                try {
                    drive.download(f.id, tmp)
                } catch (e: DriveException) {
                    if (e.code == 404) return SeenCopy.Gone
                    throw e
                }
                val ids = try {
                    JSONObject(tmp.readText()).optJSONArray("ids")
                } catch (e: JSONException) {
                    null
                }
                if (ids == null) {
                    if (tmp.length() != f.size) throw IOException("seen file cut off: ${tmp.length()} of ${f.size} bytes")
                    return SeenCopy.Corrupt
                }
                return SeenCopy.Ids((0 until ids.length()).mapNotNull { ids.optString(it).ifEmpty { null } }.toSet())
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

    private sealed interface SeenCopy {
        data class Ids(val ids: Set<String>) : SeenCopy
        data object Corrupt : SeenCopy
        data object Gone : SeenCopy
    }

    private companion object {
        /** Process-wide: the wake work, a backup run and the button must not download the same file twice. */
        val RUNNING = AtomicBoolean(false)
    }
}

/** A check that cannot go on, with its own status text. */
private class InboxFailure(val text: String) : Exception(text)

/**
 * Records an inbox check that could not run at all (e.g. no readonly token for this run) as the
 * inbox's error; the backup's own fields are untouched. The text comes from [inboxErrorText], so
 * the failure is classified by its exception class (a [DriveAuthException] is lost access). Only a
 * [DriveAuthException] also sets [BackupStatus.inboxNeedsReauth] (never a 403 or a token hiccup).
 */
internal fun recordInboxFailure(store: BackupStore, e: Exception) {
    store.update {
        it.copy(inboxError = inboxErrorText(e), inboxNeedsReauth = it.inboxNeedsReauth || e is DriveAuthException)
    }
}

/**
 * Lost readonly access (token rejected, or a 403 from a listing) asks the owner to turn the
 * checkbox on again. DriveRest drops the error body, so a rate-limit 403 cannot be told apart and
 * reads the same.
 */
internal fun inboxErrorText(e: Throwable): String = when {
    e is InboxFailure -> e.text
    e is DriveAuthException -> INBOX_ERROR_NO_ACCESS
    e is DriveException && e.code == 403 -> INBOX_ERROR_NO_ACCESS
    else -> backupErrorText(e, INBOX_ERROR_OTHER)
}
