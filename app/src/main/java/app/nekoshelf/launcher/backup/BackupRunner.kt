package app.nekoshelf.launcher.backup

import app.nekoshelf.launcher.backup.drive.Drive
import app.nekoshelf.launcher.backup.drive.DriveAuthException
import app.nekoshelf.launcher.backup.drive.DriveException
import app.nekoshelf.launcher.backup.drive.RemoteFile
import app.nekoshelf.launcher.backup.drive.TokenUnavailableException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * One backup run, and the Drive lookups restore needs. Blocking: call off the main thread.
 *
 * Drive layout (all created by the app, so `drive.file` sees them):
 * - `Nekoshelf/koreader/koreader-YYYY-MM-DD.zip`, `Nekoshelf/manga/manga-YYYY-MM-DD.tachibk` —
 *   appProperties `kind=koreader` / `kind=manga`; one per local day, the newest 14 kept;
 * - `Nekoshelf/books/<relative folders>/<file>` — appProperties from [bookAppProperties]
 *   (`kind=book`, `pid`, `size`, `mtime`, path chunks `p0..pN`), so the whole index is one
 *   `listByProp("kind", "book")`. Never deleted when the book leaves the reader; a changed book
 *   replaces its own older copy.
 *
 * Runs never overlap within the process: a [run] while another is in progress returns the stored
 * status without doing anything. Nor does a run while a restore offer or a restore holds backups
 * (`restoreOfferPending`, ruling R28): a run queued earlier must not snapshot a half-restored reader.
 *
 * [mangaAvailable] says whether «Манга» is installed with the bridge permission granted: only then
 * is its backup asked for, and only its absence counts as "skipped".
 *
 * [readonly]: the Drive token carries `drive.readonly` (the inbox checkbox is on and granted), so
 * the owner's own files under `Nekoshelf/books` are visible: a local book with *any* file at its
 * path there counts as on Drive and is never uploaded (no duplicate of a book the owner dropped
 * in), and [downloadBook] can fetch such a file. Without it nothing is listed beyond the app's own
 * files, exactly as before the inbox. [inbox] (null when the checkbox is off or not granted) runs
 * first in every run, on an [unmetered] network only; whatever it does, the backup goes on and
 * succeeds or fails as it would without it (the inbox keeps its own status fields).
 *
 * The `Nekoshelf` root is the one [DriveRoots.resolve] finds (the inbox uses the same), created
 * only when there is none.
 */
class BackupRunner(
    private val drive: Drive,
    private val store: BackupStore,
    private val koreaderRoot: File,
    private val homeDir: () -> File,
    private val mangaBackup: () -> InputStream?,
    private val mangaAvailable: () -> Boolean,
    private val cacheDir: File,
    private val clock: () -> Long,
    private val zone: ZoneId,
    private val inbox: InboxRunner? = null,
    private val unmetered: () -> Boolean = { false },
    private val readonly: Boolean = inbox != null,
) {
    /**
     * KOReader snapshot → «Манга» (skipped only when unavailable) → retention → books (if
     * [mayUploadBooks]; [powerNow] is asked again before each further book, and the books step
     * stops — not a failure — once the rule no longer allows them, e.g. unplugged during the first
     * sync). Records the outcome in [store] and returns it. A failed automatic run that is not
     * network-class is counted for the day (R32).
     *
     * - A «Манга» backup that fails (no stream, an error, empty or not gzip) does not stop the rest;
     *   if everything else worked the run counts as a success *and* records «Манга не ответила» as
     *   a later failure.
     * - A book Drive rejects does not stop the other books; the run then ends as a failure with the
     *   first book's error. Network and sign-in errors stop the run at once.
     */
    fun run(power: Power, manual: Boolean, powerNow: () -> Power = { power }): BackupStatus {
        if (!RUNNING.compareAndSet(false, true)) return store.load()
        try {
            return runLocked(power, manual, powerNow)
        } finally {
            RUNNING.set(false)
        }
    }

    private fun runLocked(power: Power, manual: Boolean, powerNow: () -> Power): BackupStatus {
        // The hold (R28), auto and manual alike: the owner's restore answer comes first.
        store.load().let { if (it.restoreOfferPending) return it }
        runInbox()
        val start = store.load()
        if (start.restoreOfferPending) return start
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pass = Pass(start)
        val outcome = try {
            val work = newWorkDir()
            try {
                pass.backUp(today, power, manual, powerNow, work)
            } finally {
                work.deleteRecursively()
            }
        } catch (e: Exception) {
            Outcome(success = false, error = backupErrorText(e), networkClass = isNetworkClass(e))
        }
        val c = pass.counts
        // Merged into the current status: a restore answer during the run is kept. A sign-out or a
        // different account since the start makes this run's results meaningless: leave them out.
        val result = store.update { cur ->
            if (!cur.signedIn || cur.account != start.account) return@update cur
            var s = cur.copy(
                koreaderSnapshots = c.koreader,
                mangaSnapshots = c.manga,
                booksOnDrive = c.onDrive,
                booksLocal = c.local,
                booksPending = c.pending,
                booksCaughtUpOnce = cur.booksCaughtUpOnce || (c.booksDone && c.pending == 0),
            )
            if (outcome.success) s = s.copy(lastSuccessAt = now, lastError = null)
            if (outcome.error != null) {
                // After a success it is a later, separate failure (the screen shows the newest of the two).
                val at = if (outcome.success) maxOf(clock(), now + 1) else now
                s = s.copy(lastFailureAt = at, lastError = outcome.error)
            }
            if (!manual && !outcome.success && !outcome.networkClass) s = s.countFailedAuto(today)
            s
        }
        if (outcome.success) store.clearResumeSessions()
        return result
    }

    /**
     * The inbox step: first, so the books it brings are in this run's snapshot and seen on Drive.
     * A check already running elsewhere (the wake's inbox work) makes it return at once; the backup
     * goes on, and its readonly view keeps it from uploading what that check brings.
     */
    private fun runInbox() {
        val step = inbox ?: return
        try {
            if (unmetered()) step.run()
        } catch (e: Exception) {
            // Never the backup's failure; InboxRunner records its own errors.
        }
    }

    /** A fresh folder of this run under `backup-work/`; leftovers older than a day are removed. */
    private fun newWorkDir(): File {
        val base = File(cacheDir, WORK_DIR)
        base.mkdirs()
        val cutoff = System.currentTimeMillis() - STALE_WORK_MS
        base.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        return Files.createTempDirectory(base.toPath(), "run-").toFile()
    }

    private data class Outcome(val success: Boolean, val error: String?, val networkClass: Boolean = false)

    private class Counts(s: BackupStatus) {
        var koreader = s.koreaderSnapshots
        var manga = s.mangaSnapshots
        var onDrive = s.booksOnDrive
        var local = s.booksLocal
        var pending = s.booksPending
        var booksDone = false
    }

    /** State of one run: counts, folder ids found (by "parentId/name"), files uploaded. */
    private inner class Pass(private val start: BackupStatus) {
        val counts = Counts(start)
        private val folders = HashMap<String, String>()
        private val fresh = HashSet<String>()

        fun backUp(today: LocalDate, power: Power, manual: Boolean, powerNow: () -> Power, work: File): Outcome {
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
            val root = DriveRoots.resolve(drive)?.id ?: drive.folder(ROOT, null)
            replaceSnapshot(folder(KIND_KOREADER, root), zip, KIND_KOREADER)
            zip.delete()

            var mangaError: String? = null
            val mangaFile = File(work, "$KIND_MANGA-$today.tachibk")
            val haveManga = try {
                copyManga(mangaFile)
            } catch (e: StepFailure) {
                mangaError = e.text
                false
            }
            if (haveManga) replaceSnapshot(folder(KIND_MANGA, root), mangaFile, KIND_MANGA)
            mangaFile.delete()

            counts.koreader = retain(KIND_KOREADER, KOREADER_NAME)
            counts.manga = retain(KIND_MANGA, MANGA_NAME)

            val bookError = backUpBooks(manifest, home, root, mayUploadBooks(start, power, manual)) {
                mayUploadBooks(start, powerNow(), manual)
            }
            return when {
                bookError != null -> Outcome(success = false, error = mangaError ?: backupErrorText(bookError))
                else -> Outcome(success = true, error = mangaError)
            }
        }

        /**
         * Copies «Манга»'s backup into [to]. False (skipped) only when «Манга» is not installed or
         * the bridge permission is missing; once it is available, no stream, a failing or empty
         * stream, or one that is not gzip (a `.tachibk` is) is a [MANGA_FAILED] failure.
         */
        private fun copyManga(to: File): Boolean {
            val available = try {
                mangaAvailable()
            } catch (e: Exception) {
                throw StepFailure(MANGA_FAILED, e)
            }
            if (!available) return false
            val input = try {
                mangaBackup()
            } catch (e: Exception) {
                throw StepFailure(MANGA_FAILED, e)
            } ?: throw StepFailure(MANGA_FAILED, IOException("no backup stream"))
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
            if (!isGzip(to)) throw StepFailure(MANGA_FAILED, IOException("backup is not gzip"))
            return true
        }

        /** Uploads today's snapshot, then deletes the older file(s) of the same name (same-day rerun). */
        private fun replaceSnapshot(parent: String, file: File, kind: String) {
            val old = drive.listByProp(PROP_KIND, kind).filter { it.name == file.name }
            val up = upload(parent, file.name, file, mapOf(PROP_KIND to kind))
            old.filter { it.id != up.id }.forEach { deleteRemote(it.id) }
        }

        /** Deletes all but the newest [KEEP] snapshots of [kind] (never one uploaded now); returns how many remain. */
        private fun retain(kind: String, pattern: Regex): Int {
            val files = drive.listByProp(PROP_KIND, kind).filter { pattern.matches(it.name) }
            val drop = snapshotsToDelete(files.map { it.name }, KEEP).toSet()
            // A clock that went backwards makes today's file look old: it stays anyway.
            val gone = files.filter { it.name in drop && it.id !in fresh }
            gone.forEach { deleteRemote(it.id) }
            return files.size - gone.size
        }

        /**
         * Returns the first per-book error (the other books were still tried), or null. [stillAllowed]
         * is asked before every book after the first; once it says no, the rest stay pending.
         */
        private fun backUpBooks(
            manifest: List<BookEntry>,
            home: File,
            root: String,
            upload: Boolean,
            stillAllowed: () -> Boolean,
        ): Exception? {
            var firstError: Exception? = null
            // Without network or access every other book would fail the same way.
            fun bookFailed(e: Exception) {
                if (e is DriveAuthException || isNetworkClass(e)) throw e
                if (firstError == null) firstError = e
            }
            val index = HashMap<String, List<RemoteFile>>()
            for (f in drive.listByProp(PROP_KIND, KIND_BOOK)) {
                val path = bookPathOf(f.props) ?: continue
                index[path] = index[path].orEmpty() + f
            }
            // Local books with a file of any creator at their path (only visible with readonly).
            val userPaths = if (readonly) userBookPaths(root) else emptySet()
            val userPresent = HashSet<String>()
            val pending = ArrayList<BookEntry>()
            for (b in manifest) {
                val copies = index[b.path].orEmpty()
                val keep = copies.firstOrNull { it.isCopyOf(b) }
                if (keep == null) {
                    if (b.path in userPaths) userPresent += b.path else pending += b
                } else if (copies.size > 1) {
                    // Older versions or duplicates of a book whose current copy is on Drive.
                    try {
                        copies.filter { it.id != keep.id }.forEach { deleteRemote(it.id) }
                        index[b.path] = listOf(keep)
                    } catch (e: Exception) {
                        bookFailed(e)
                    }
                }
            }
            fun onDrive() = if (userPresent.isEmpty()) index.size else (index.keys + userPresent).size
            counts.local = manifest.size
            counts.onDrive = onDrive()
            counts.pending = pending.size
            if (!upload) {
                counts.booksDone = true
                return firstError
            }
            var left = pending.size
            for ((i, b) in pending.withIndex()) {
                if (i > 0 && !stillAllowed()) break
                val file = File(home, b.path)
                if (!file.isFile) { // removed from the reader since the scan; the next scan will not list it
                    counts.pending = --left
                    continue
                }
                try {
                    val up = upload(bookFolder(root, b.path), b.path.substringAfterLast('/'), file, bookAppProperties(b))
                    // Same book, new version (size/mtime changed): the old copy goes once the new one is up.
                    index[b.path].orEmpty().filter { it.id != up.id }.forEach { deleteRemote(it.id) }
                    index[b.path] = listOf(up)
                    counts.onDrive = onDrive()
                    counts.pending = --left
                } catch (e: Exception) {
                    bookFailed(e)
                }
            }
            counts.booksDone = true
            return firstError
        }

        /**
         * Relative paths of every file under `Nekoshelf/books` (all folders of that name; creates
         * none), the app's or the owner's. Google-native entries (Docs, shortcuts) are not copies
         * of a book and do not count.
         */
        private fun userBookPaths(root: String): Set<String> =
            drive.findChildren(root, BOOKS).filter { it.isFolder() }
                .flatMap { drive.listTree(it.id) }
                .filter { !it.file.mimeType.startsWith(GOOGLE_NATIVE_PREFIX) }
                .mapTo(HashSet()) { it.relativePath }

        private fun upload(parent: String, name: String, file: File, props: Map<String, String>): RemoteFile =
            drive.upload(parent, name, file, props, store).also { fresh += it.id }

        private fun bookFolder(root: String, path: String): String {
            var id = folder(BOOKS, root)
            for (seg in path.split('/').dropLast(1)) id = folder(seg, id)
            return id
        }

        private fun folder(name: String, parent: String?): String =
            folders.getOrPut("$parent/$name") { drive.folder(name, parent) }
    }

    /** A 404 means someone (an earlier run, the owner) already deleted it. */
    private fun deleteRemote(id: String) {
        try {
            drive.delete(id)
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }

    // ---- restore side ----------------------------------------------------------------------

    /** Whether Drive holds any snapshot from an earlier backup. */
    fun hasRemoteBackups(): Boolean = newest(KIND_KOREADER, KOREADER_NAME) != null || newest(KIND_MANGA, MANGA_NAME) != null

    /**
     * The day of the newest backup on Drive, from the newest KOReader snapshot's name (or, when
     * there is none, the newest «Манга» one's); null when Drive holds neither.
     */
    fun latestSnapshotDate(): LocalDate? {
        val f = newest(KIND_KOREADER, KOREADER_NAME) ?: newest(KIND_MANGA, MANGA_NAME) ?: return null
        return SNAPSHOT_DAY.find(f.name)?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() }
    }

    /** Drive name of the newest «Манга» backup (`manga-YYYY-MM-DD.tachibk`), or null when there is none. */
    fun latestMangaBackupName(): String? = latestMangaBackupFile()?.name

    /** The newest «Манга» backup on Drive (its name and id), or null when there is none. */
    fun latestMangaBackupFile(): RemoteFile? = newest(KIND_MANGA, MANGA_NAME)

    /** Downloads exactly [file] (e.g. from [latestMangaBackupFile]) into [to], by its id. */
    fun download(file: RemoteFile, to: File) = drive.download(file.id, to)

    /** Downloads the newest KOReader snapshot into [to]; false when there is none. */
    fun latestSnapshot(to: File): Boolean = downloadNewest(KIND_KOREADER, KOREADER_NAME, to)

    /** Downloads the newest «Манга» backup into [to]; false when there is none. */
    fun latestMangaBackup(to: File): Boolean = downloadNewest(KIND_MANGA, MANGA_NAME, to)

    /**
     * Downloads the Drive copy of [entry] (by its relative path) into [to]: the backup's own copy;
     * failing that, with [readonly], any file at that path under `Nekoshelf/books` (e.g. one the
     * inbox brought). Throws [FileNotFoundException] if Drive has none, [BookNeedsInboxException]
     * when, without readonly, the owner's files cannot even be looked at.
     */
    fun downloadBook(entry: BookEntry, to: File) {
        fun ofPath(files: List<RemoteFile>) =
            files.filter { it.props[PROP_KIND] == KIND_BOOK && bookPathOf(it.props) == entry.path }
        val copies = ofPath(drive.listByProp(PROP_PID, pathId(entry.path)))
            .ifEmpty { ofPath(drive.listByProp(PROP_KIND, KIND_BOOK)) }
        val f = copies.firstOrNull { it.isCopyOf(entry) } ?: copies.firstOrNull()
            ?: if (readonly) atPath(entry) else throw BookNeedsInboxException()
        drive.download(f.id, to)
    }

    /** Any non-folder, non-Google-native file at [entry]'s path under `Nekoshelf/books`, one of the same size first. */
    private fun atPath(entry: BookEntry): RemoteFile {
        val root = DriveRoots.resolve(drive) ?: throw FileNotFoundException("book not on Google Drive")
        val segments = entry.path.split('/')
        var dirs = drive.findChildren(root.id, BOOKS).filter { it.isFolder() }
        for (seg in segments.dropLast(1)) dirs = dirs.flatMap { d -> drive.findChildren(d.id, seg).filter { it.isFolder() } }
        val files = dirs.flatMap { d -> drive.findChildren(d.id, segments.last()) }
            .filter { !it.isFolder() && !it.mimeType.startsWith(GOOGLE_NATIVE_PREFIX) }
        return files.firstOrNull { it.size == entry.size } ?: files.firstOrNull()
            ?: throw FileNotFoundException("book not on Google Drive")
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

    private companion object {
        /** Process-wide: two runners (worker + "Сделать сейчас") must not share Drive work. */
        val RUNNING = AtomicBoolean(false)
        const val WORK_DIR = "backup-work"
        const val STALE_WORK_MS = 24L * 60 * 60 * 1000
        const val ROOT = DRIVE_ROOT
        const val BOOKS = DRIVE_BOOKS
        const val KEEP = 14
        const val KIND_KOREADER = "koreader"
        const val KIND_MANGA = "manga"
        val KOREADER_NAME = Regex("""koreader-\d{4}-\d{2}-\d{2}\.zip""")
        val MANGA_NAME = Regex("""manga-\d{4}-\d{2}-\d{2}\.tachibk""")
        val SNAPSHOT_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

// ---- book appProperties ------------------------------------------------------------------------

/** The app's folder in My Drive and the books mirror inside it (the inbox reads it too). */
internal const val DRIVE_ROOT = "Nekoshelf"
internal const val DRIVE_BOOKS = "books"

internal const val PROP_KIND = "kind"
private const val PROP_PID = "pid"
private const val PROP_SIZE = "size"
private const val PROP_MTIME = "mtime"
private const val PROP_PATH_CHUNK = "p"
internal const val KIND_BOOK = "book"

/** Drive's limit per appProperty: key + value, in UTF-8 bytes. */
internal const val DRIVE_PROP_MAX_BYTES = 124

/** Drive's limit on private properties one app may set on a file. */
internal const val DRIVE_PROPS_MAX_COUNT = 30

/** Lowercase hex SHA-1 of the UTF-8 relative path: a fixed-size key to find one book by path. */
internal fun pathId(path: String): String =
    MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * `kind=book`, `pid`, `size`, `mtime`, and the path in chunks `p0`, `p1`, … each cut at a character
 * boundary so key + value fit [DRIVE_PROP_MAX_BYTES] (a Cyrillic path is 2 bytes per letter).
 * Throws [IOException] for a path too long for Drive's property count.
 */
internal fun bookAppProperties(b: BookEntry): Map<String, String> {
    val props = linkedMapOf(
        PROP_KIND to KIND_BOOK,
        PROP_PID to pathId(b.path),
        PROP_SIZE to b.size.toString(),
        PROP_MTIME to b.mtime.toString(),
    )
    val chunks = pathChunks(b.path)
    if (props.size + chunks.size > DRIVE_PROPS_MAX_COUNT) throw IOException("book path too long for Google Drive")
    chunks.forEachIndexed { i, c -> props["$PROP_PATH_CHUNK$i"] = c }
    return props
}

/** The relative path rebuilt from `p0..pN`, or null when the file has none. */
internal fun bookPathOf(props: Map<String, String>): String? {
    val first = props["${PROP_PATH_CHUNK}0"] ?: return null
    val sb = StringBuilder(first)
    var i = 1
    while (true) sb.append(props["$PROP_PATH_CHUNK${i++}"] ?: break)
    return sb.toString()
}

private fun pathChunks(path: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < path.length) {
        val budget = DRIVE_PROP_MAX_BYTES - "$PROP_PATH_CHUNK${out.size}".length
        val chunk = StringBuilder()
        var bytes = 0
        while (i < path.length) {
            val cp = path.codePointAt(i)
            val n = utf8Length(cp)
            if (bytes + n > budget) break
            chunk.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        out += chunk.toString()
    }
    return out
}

/** Bytes of [cp] in UTF-8 (a lone surrogate counts 3; the encoder writes 1, so this never undercounts). */
private fun utf8Length(cp: Int) = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

// ---- errors ------------------------------------------------------------------------------------

/** A book only the owner's own files on Drive may hold, and the readonly grant to look is missing. */
class BookNeedsInboxException : FileNotFoundException(RESTORE_HINT_INBOX)

/** A step failed in a way that has its own text; [cause] is kept for logs only. */
private class StepFailure(val text: String, cause: Throwable) : Exception(text, cause)

internal const val SNAPSHOT_FAILED = "не удалось собрать бэкап KOReader"
internal const val MANGA_FAILED = "Манга не ответила"
const val BACKUP_ERROR_AUTH = "Google отозвал доступ — войдите заново"
const val BACKUP_ERROR_NO_NETWORK = "нет сети"
const val RESTORE_HINT_INBOX = "включите \"Забирать новые книги\" и повторите"
private const val BACKUP_ERROR_OTHER = "не удалось сделать бэкап"

/**
 * Failures worth retrying at every wake (R32): no or bad network, or Play services not giving a
 * token for now. InterruptedIOException covers SocketTimeoutException; SocketException covers
 * ConnectException and "connection reset"; SSLException is what a dropped Wi‑Fi or captive portal
 * gives mid-TLS.
 */
internal fun isNetworkClass(e: Throwable) =
    e is UnknownHostException || e is SocketException || e is InterruptedIOException || e is SSLException ||
        e is TokenUnavailableException

/** A `.tachibk` is gzip: it starts with the bytes 1f 8b. */
private fun isGzip(f: File): Boolean = f.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }

/**
 * The short Russian text the backup screen shows for [e]; [fallback] when nothing more specific
 * is known (the restore and sign-in paths pass their own). Never includes the exception's
 * message (it may hold paths or server text).
 */
fun backupErrorText(e: Throwable, fallback: String = BACKUP_ERROR_OTHER): String = when {
    e is StepFailure -> e.text
    e is AmbiguousRootException -> INBOX_ERROR_MANY_ROOTS
    e is BookNeedsInboxException -> RESTORE_HINT_INBOX
    e is DriveAuthException -> BACKUP_ERROR_AUTH
    e is DriveException -> "ошибка Google Диска (код ${e.code})"
    isNetworkClass(e) -> BACKUP_ERROR_NO_NETWORK
    else -> fallback
}
