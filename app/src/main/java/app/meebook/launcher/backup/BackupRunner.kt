package app.meebook.launcher.backup

import app.meebook.launcher.backup.drive.Drive
import app.meebook.launcher.backup.drive.DriveAuthException
import app.meebook.launcher.backup.drive.DriveException
import app.meebook.launcher.backup.drive.RemoteFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.net.ssl.SSLException

/**
 * One backup run, and the Drive lookups restore needs. Blocking: call off the main thread.
 *
 * Drive layout (all created by the app, so `drive.file` sees them):
 * - `Meebook/koreader/koreader-YYYY-MM-DD.zip`, `Meebook/manga/manga-YYYY-MM-DD.tachibk` —
 *   appProperties `kind=koreader` / `kind=manga`; one per local day, the newest 14 kept;
 * - `Meebook/books/<relative folders>/<file>` — appProperties `kind=book`, `path`, `size`, `mtime`,
 *   so the whole index is one `listByProp("kind", "book")`. Never deleted when the book leaves the
 *   reader; a changed book replaces its own older copy.
 */
class BackupRunner(
    private val drive: Drive,
    private val store: BackupStore,
    private val koreaderRoot: File,
    private val homeDir: () -> File,
    private val mangaBackup: () -> InputStream?,
    private val cacheDir: File,
    private val clock: () -> Long,
    private val zone: ZoneId,
) {
    /** Folder ids found during the current run, by "parentId/name". */
    private val folders = HashMap<String, String>()

    private class Counts(s: BackupStatus) {
        var koreader = s.koreaderSnapshots
        var manga = s.mangaSnapshots
        var onDrive = s.booksOnDrive
        var local = s.booksLocal
        var pending = s.booksPending
        var booksDone = false
    }

    /**
     * KOReader snapshot → «Манга» (no stream = skipped) → retention → books (if [mayUploadBooks]).
     * Records the outcome in [store] and returns it. A «Манга» stream that fails mid-read does not
     * stop the books; the run still ends as a failure with «Манга не ответила».
     */
    fun run(power: Power, manual: Boolean): BackupStatus {
        folders.clear()
        val start = store.load()
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val counts = Counts(start)
        val work = File(cacheDir, WORK_DIR)
        val error: String? = try {
            work.deleteRecursively()
            work.mkdirs()
            backUp(today, power, manual, start, work, counts)
        } catch (e: Exception) {
            backupErrorText(e)
        } finally {
            work.deleteRecursively()
        }
        // Merged into the current status: a sign-in/out or restore answer during the run is kept.
        val result = store.update { cur ->
            val s = cur.copy(
                lastAttemptDay = today.toString(),
                koreaderSnapshots = counts.koreader,
                mangaSnapshots = counts.manga,
                booksOnDrive = counts.onDrive,
                booksLocal = counts.local,
                booksPending = counts.pending,
                booksCaughtUpOnce = cur.booksCaughtUpOnce || (counts.booksDone && counts.pending == 0),
            )
            if (error == null) s.copy(lastSuccessAt = now, lastError = null) else s.copy(lastFailureAt = now, lastError = error)
        }
        if (error == null) store.clearResumeSessions()
        return result
    }

    /** Returns null on success or the deferred «Манга» error; throws on anything that stops the run. */
    private fun backUp(today: LocalDate, power: Power, manual: Boolean, start: BackupStatus, work: File, c: Counts): String? {
        val zip = File(work, "$KIND_KOREADER-$today.zip")
        val home = try {
            homeDir()
        } catch (e: Exception) {
            throw StepFailure(SNAPSHOT_FAILED, e)
        }
        val manifest = try {
            KoreaderSnapshot.build(koreaderRoot, home, zip)
        } catch (e: Exception) {
            throw StepFailure(SNAPSHOT_FAILED, e)
        }
        val root = folder(ROOT, null)
        replaceSnapshot(folder(KIND_KOREADER, root), zip, KIND_KOREADER)
        zip.delete()

        var deferred: String? = null
        val mangaFile = File(work, "$KIND_MANGA-$today.tachibk")
        val haveManga = try {
            copyManga(mangaFile)
        } catch (e: StepFailure) {
            deferred = e.text
            false
        }
        if (haveManga) replaceSnapshot(folder(KIND_MANGA, root), mangaFile, KIND_MANGA)
        mangaFile.delete()

        c.koreader = retain(KIND_KOREADER, KOREADER_NAME)
        c.manga = retain(KIND_MANGA, MANGA_NAME)

        backUpBooks(manifest, home, root, mayUploadBooks(start, power, manual), c)
        return deferred
    }

    /** Copies «Манга»'s backup into [to]. False when «Манга» gave no stream (unavailable or too old). */
    private fun copyManga(to: File): Boolean {
        val input = try {
            mangaBackup()
        } catch (e: Exception) {
            throw StepFailure(MANGA_FAILED, e)
        } ?: return false
        var total = 0L
        input.use { inp ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = try {
                        inp.read(buf)
                    } catch (e: IOException) {
                        throw StepFailure(MANGA_FAILED, e)
                    }
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        // An empty .tachibk would become "the newest" backup while holding nothing.
        if (total == 0L) throw StepFailure(MANGA_FAILED, IOException("empty backup stream"))
        return true
    }

    /** Uploads today's snapshot, then deletes the older file(s) of the same name (same-day rerun). */
    private fun replaceSnapshot(parent: String, file: File, kind: String) {
        val old = drive.listByProp(PROP_KIND, kind).filter { it.name == file.name }
        val up = drive.upload(parent, file.name, file, mapOf(PROP_KIND to kind), store)
        old.filter { it.id != up.id }.forEach { drive.delete(it.id) }
    }

    /** Deletes all but the newest [KEEP] snapshots of [kind]; returns how many remain. */
    private fun retain(kind: String, pattern: Regex): Int {
        val files = drive.listByProp(PROP_KIND, kind).filter { pattern.matches(it.name) }
        val drop = snapshotsToDelete(files.map { it.name }, KEEP).toSet()
        files.filter { it.name in drop }.forEach { drive.delete(it.id) }
        return files.count { it.name !in drop }
    }

    private fun backUpBooks(manifest: List<BookEntry>, home: File, root: String, upload: Boolean, c: Counts) {
        val index = HashMap<String, List<RemoteFile>>()
        for (f in drive.listByProp(PROP_KIND, KIND_BOOK)) {
            val path = f.props[PROP_PATH] ?: continue
            index[path] = index[path].orEmpty() + f
        }
        val pending = manifest.filter { b -> index[b.path].orEmpty().none { it.isCopyOf(b) } }
        c.local = manifest.size
        c.onDrive = index.size
        c.pending = pending.size
        if (!upload) {
            c.booksDone = true
            return
        }
        var left = pending.size
        for (b in pending) {
            val file = File(home, b.path)
            if (file.isFile) {
                val up = drive.upload(bookFolder(root, b.path), b.path.substringAfterLast('/'), file, b.props(), store)
                // Same book, new version (size/mtime changed): the old copy goes once the new one is up.
                index[b.path].orEmpty().filter { it.id != up.id }.forEach { drive.delete(it.id) }
                index[b.path] = listOf(up)
                c.onDrive = index.size
            } // else: removed from the reader since the scan; the next scan will not list it.
            c.pending = --left
        }
        c.booksDone = true
    }

    private fun bookFolder(root: String, path: String): String {
        var id = folder(BOOKS, root)
        for (seg in path.split('/').dropLast(1)) id = folder(seg, id)
        return id
    }

    private fun folder(name: String, parent: String?): String =
        folders.getOrPut("$parent/$name") { drive.folder(name, parent) }

    // ---- restore side ----------------------------------------------------------------------

    /** Whether Drive holds any snapshot from an earlier backup. */
    fun hasRemoteBackups(): Boolean = newest(KIND_KOREADER, KOREADER_NAME) != null || newest(KIND_MANGA, MANGA_NAME) != null

    /** Downloads the newest KOReader snapshot into [to]; false when there is none. */
    fun latestSnapshot(to: File): Boolean = downloadNewest(KIND_KOREADER, KOREADER_NAME, to)

    /** Downloads the newest «Манга» backup into [to]; false when there is none. */
    fun latestMangaBackup(to: File): Boolean = downloadNewest(KIND_MANGA, MANGA_NAME, to)

    /** Downloads the Drive copy of [entry] (by its relative path) into [to]. Throws if Drive has none. */
    fun downloadBook(entry: BookEntry, to: File) {
        val copies = drive.listByProp(PROP_PATH, entry.path).filter { it.props[PROP_KIND] == KIND_BOOK }
        val f = copies.firstOrNull { it.isCopyOf(entry) } ?: copies.firstOrNull()
            ?: throw FileNotFoundException("book not on Google Drive")
        drive.download(f.id, to)
    }

    private fun downloadNewest(kind: String, pattern: Regex, to: File): Boolean {
        val f = newest(kind, pattern) ?: return false
        drive.download(f.id, to)
        return true
    }

    /** ISO dates in the names sort lexicographically. */
    private fun newest(kind: String, pattern: Regex): RemoteFile? =
        drive.listByProp(PROP_KIND, kind).filter { pattern.matches(it.name) }.maxByOrNull { it.name }

    private fun RemoteFile.isCopyOf(b: BookEntry) =
        props[PROP_SIZE] == b.size.toString() && props[PROP_MTIME] == b.mtime.toString()

    private fun BookEntry.props() =
        mapOf(PROP_KIND to KIND_BOOK, PROP_PATH to path, PROP_SIZE to size.toString(), PROP_MTIME to mtime.toString())

    private companion object {
        const val WORK_DIR = "backup-work"
        const val ROOT = "Meebook"
        const val BOOKS = "books"
        const val KEEP = 14
        const val PROP_KIND = "kind"
        const val PROP_PATH = "path"
        const val PROP_SIZE = "size"
        const val PROP_MTIME = "mtime"
        const val KIND_KOREADER = "koreader"
        const val KIND_MANGA = "manga"
        const val KIND_BOOK = "book"
        val KOREADER_NAME = Regex("""koreader-\d{4}-\d{2}-\d{2}\.zip""")
        val MANGA_NAME = Regex("""manga-\d{4}-\d{2}-\d{2}\.tachibk""")
    }
}

/** A step failed in a way that has its own text; [cause] is kept for logs only. */
private class StepFailure(val text: String, cause: Throwable) : Exception(text, cause)

internal const val SNAPSHOT_FAILED = "не удалось собрать бэкап KOReader"
internal const val MANGA_FAILED = "Манга не ответила"
const val BACKUP_ERROR_AUTH = "Google отозвал доступ — войдите заново"
const val BACKUP_ERROR_NO_NETWORK = "нет сети"
private const val BACKUP_ERROR_OTHER = "не удалось сделать бэкап"

/**
 * The short Russian text the backup screen shows for [e]. Never includes the exception's
 * message (it may hold paths or server text).
 */
fun backupErrorText(e: Throwable): String = when (e) {
    is StepFailure -> e.text
    is DriveAuthException -> BACKUP_ERROR_AUTH
    is DriveException -> "ошибка Google Диска (код ${e.code})"
    // InterruptedIOException covers SocketTimeoutException; SocketException covers ConnectException
    // and "connection reset"; SSLException is what a dropped Wi‑Fi or captive portal gives mid-TLS.
    is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException -> BACKUP_ERROR_NO_NETWORK
    else -> BACKUP_ERROR_OTHER
}
