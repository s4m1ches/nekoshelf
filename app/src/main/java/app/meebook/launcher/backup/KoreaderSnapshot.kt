package app.meebook.launcher.backup

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A file in the books home dir: [path] is relative to it, `/`-separated; [mtime] in ms. */
data class BookEntry(val path: String, val size: Long, val mtime: Long)

/**
 * Every regular file under [homeDir] (any extension), skipping names that start with "." and
 * `*.sdr` folders (case-insensitive) — the same walk rules as `BookScanner`. Sorted by path.
 * Throws [IOException] when [homeDir] (or a folder inside it) cannot be listed: an empty
 * manifest from an unreadable folder would later restore nothing.
 */
fun scanBooks(homeDir: File): List<BookEntry> {
    val out = ArrayList<BookEntry>()
    fun walk(dir: File, prefix: String) {
        for (c in listOrThrow(dir)) {
            val name = c.name
            if (name.startsWith(".")) continue
            if (c.isDirectory) {
                if (!isSdrName(name)) walk(c, "$prefix$name/")
            } else if (c.isFile) {
                out += BookEntry("$prefix$name", c.length(), c.lastModified())
            }
        }
    }
    walk(requireReadableDir(homeDir), "")
    return out.sortedBy { it.path }
}

/**
 * One daily KOReader snapshot zip. Entries:
 * - `koreader/<path under koreaderRoot>` for the files [isSnapshotKoreaderPath] accepts;
 *   `settings/statistics.sqlite3` is a single self-contained DB (its `-wal` checkpointed into it,
 *   integrity-checked) and never comes with `-wal`/`-shm`/`-journal` entries;
 * - `books/<rel>.sdr/...` for every `.sdr` folder under the books home dir;
 * - [MANIFEST_ENTRY]: `[{path,size,mtime}]` of [scanBooks].
 */
object KoreaderSnapshot {
    /**
     * Writes the snapshot to [out] (replacing it; written to a temp file next to it and renamed
     * at the end, so [out] is never a truncated zip) and returns the books manifest inside it.
     * Throws when either root is not a readable folder or the stats DB fails its integrity check.
     */
    fun build(koreaderRoot: File, homeDir: File, out: File): List<BookEntry> {
        requireReadableDir(koreaderRoot)
        val manifest = scanBooks(homeDir)
        val dir = out.absoluteFile.parentFile ?: throw IOException("no folder for $out")
        dir.mkdirs()
        // Leftovers of a build that was killed (our own ".snapshot-*" names only).
        dir.listFiles()?.forEach { if (it.name.startsWith(SNAPSHOT_TEMP_PREFIX)) it.deleteRecursively() }
        val tmp = File.createTempFile(SNAPSHOT_TEMP_PREFIX, ".tmp", dir)
        val work = Files.createTempDirectory(dir.toPath(), "${SNAPSHOT_TEMP_PREFIX}stats-").toFile()
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                for ((rel, f) in koreaderFiles(koreaderRoot)) {
                    if (rel == STATS_REL) addStats(zip, f, work) else addFile(zip, "$KOREADER_PREFIX$rel", f)
                }
                for ((rel, f) in sdrFiles(homeDir)) addFile(zip, "$BOOKS_PREFIX$rel", f)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
            work.deleteRecursively()
        }
        return manifest
    }

    private fun koreaderFiles(root: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            for (c in listOrThrow(dir).sortedBy { it.name }) {
                if (c.name.startsWith(".")) continue // e.g. our own ".restore-*.tmp" leftovers
                val rel = prefix + c.name
                if (c.isDirectory) walk(c, "$rel/")
                else if (c.isFile && isSnapshotKoreaderPath(rel) && !isStatsSibling(rel)) out += rel to c
            }
        }
        for (top in KOREADER_FILES) File(root, top).takeIf { it.isFile }?.let { out += top to it }
        for (dir in KOREADER_DIRS) File(root, dir).takeIf { it.isDirectory }?.let { walk(it, "$dir/") }
        return out
    }

    /** All files inside `.sdr` folders under [home] (walk skips dot-names like [scanBooks]). */
    private fun sdrFiles(home: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        fun all(dir: File, prefix: String) {
            for (c in listOrThrow(dir).sortedBy { it.name }) {
                if (c.isDirectory) all(c, "$prefix${c.name}/") else if (c.isFile) out += "$prefix${c.name}" to c
            }
        }
        fun walk(dir: File, prefix: String) {
            for (c in listOrThrow(dir).sortedBy { it.name }) {
                if (c.name.startsWith(".") || !c.isDirectory) continue
                if (isSdrName(c.name)) all(c, "$prefix${c.name}/") else walk(c, "$prefix${c.name}/")
            }
        }
        walk(home, "")
        return out
    }

    /**
     * Copies the stats DB with its `-wal`/`-journal` into [work], checkpoints the copy into one
     * file (`journal_mode=DELETE`), requires `integrity_check` = ok and zips only that file.
     * `-shm` is not copied: it holds nothing persistent and SQLite rebuilds it from the `-wal`.
     */
    private fun addStats(zip: ZipOutputStream, db: File, work: File) {
        val copy = File(work, "statistics.sqlite3")
        db.copyTo(copy, overwrite = true)
        for (s in listOf("-wal", "-journal")) {
            val src = File(db.path + s)
            if (src.isFile) src.copyTo(File(copy.path + s), overwrite = true)
        }
        // NO_LOCALIZED_COLLATORS: Android would otherwise add an android_metadata table to the copy.
        val conn = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            conn.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            conn.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            val check = conn.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (check != "ok") throw IOException("statistics.sqlite3 integrity_check: $check")
        } finally {
            conn.close()
        }
        val wal = File(copy.path + "-wal")
        if (wal.exists() && wal.length() > 0) throw IOException("statistics.sqlite3 -wal not checkpointed")
        // Never drop the stats DB silently (addFile skips files that vanished).
        if (!copy.isFile) throw IOException("statistics.sqlite3 copy disappeared")
        addFile(zip, "$KOREADER_PREFIX$STATS_REL", copy)
    }

    private fun addFile(zip: ZipOutputStream, name: String, f: File) {
        // A file can vanish while we walk (KOReader rotating *.old): skip it. One that is still
        // there but cannot be opened (Android also throws FileNotFoundException for EACCES) fails.
        val input = try {
            f.inputStream()
        } catch (e: FileNotFoundException) {
            if (f.exists()) throw e else return
        }
        input.use {
            zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
            it.copyTo(zip)
            zip.closeEntry()
        }
    }

    private fun manifestJson(books: List<BookEntry>): String {
        val arr = JSONArray()
        for (b in books) arr.put(JSONObject().put("path", b.path).put("size", b.size).put("mtime", b.mtime))
        return arr.toString()
    }
}

/** The books manifest inside a snapshot zip. Throws [IOException] if the zip has none. */
fun readManifest(zip: File): List<BookEntry> = ZipFile(zip).use { z ->
    val entry = z.getEntry(MANIFEST_ENTRY) ?: throw IOException("$MANIFEST_ENTRY missing in ${zip.name}")
    val arr = JSONArray(z.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        BookEntry(o.getString("path"), o.getLong("size"), o.getLong("mtime"))
    }
}

internal const val MANIFEST_ENTRY = "books-manifest.json"
internal const val KOREADER_PREFIX = "koreader/"
internal const val BOOKS_PREFIX = "books/"
internal const val STATS_REL = "settings/statistics.sqlite3"
internal val SQLITE_SIBLINGS = listOf("-wal", "-shm", "-journal")

private val KOREADER_FILES = listOf("history.lua", "settings.reader.lua", "defaults.custom.lua")
private val KOREADER_DIRS = listOf("settings", "patches", "styletweaks")
private const val BOOKINFO_CACHE = "bookinfo_cache.sqlite3"
private const val SNAPSHOT_TEMP_PREFIX = ".snapshot-"

internal fun isSdrName(name: String) = name.endsWith(".sdr", ignoreCase = true)

/** `settings/statistics.sqlite3-wal` / `-shm` / `-journal`: never zipped, never restored. */
internal fun isStatsSibling(rel: String) = SQLITE_SIBLINGS.any { rel == STATS_REL + it }

private fun requireReadableDir(dir: File): File {
    if (!dir.isDirectory || dir.listFiles() == null) throw IOException("not a readable folder: $dir")
    return dir
}

/** Children of [dir]; empty if it vanished meanwhile, [IOException] if it is there but unreadable. */
private fun listOrThrow(dir: File): Array<File> =
    dir.listFiles() ?: if (dir.exists()) throw IOException("cannot list $dir") else emptyArray()

/** Whether a path under the KOReader root belongs in the snapshot (and so may be restored). */
internal fun isSnapshotKoreaderPath(rel: String): Boolean {
    if (rel in KOREADER_FILES) return true
    if (KOREADER_DIRS.none { rel.startsWith("$it/") }) return false
    val name = rel.substringAfterLast('/')
    return !(name == BOOKINFO_CACHE || name.startsWith("$BOOKINFO_CACHE-"))
}

/**
 * [rel] resolved under [root], or null when it could escape it: empty, absolute, `.`/`..`/empty
 * segments, NUL, a backslash where it is a separator, or a canonical path outside [root].
 */
internal fun resolveInside(root: File, rel: String): File? {
    if (rel.isEmpty() || rel.startsWith("/") || '\u0000' in rel) return null
    if (File.separatorChar == '\\' && ('\\' in rel || ':' in rel)) return null
    if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    val target = File(root, rel)
    return try {
        val base = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        if (target.canonicalPath.startsWith(base)) target else null
    } catch (e: IOException) {
        null
    }
}
