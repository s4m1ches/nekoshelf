package app.meebook.launcher.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A file in the books home dir: [path] is relative to it, `/`-separated; [mtime] in ms. */
data class BookEntry(val path: String, val size: Long, val mtime: Long)

/**
 * Every regular file under [homeDir] (any extension), skipping names that start with "." and
 * `*.sdr` folders (case-insensitive) — the same walk rules as `BookScanner`. Sorted by path.
 */
fun scanBooks(homeDir: File): List<BookEntry> {
    val out = ArrayList<BookEntry>()
    fun walk(dir: File, prefix: String) {
        for (c in dir.listFiles() ?: return) {
            val name = c.name
            if (name.startsWith(".")) continue
            if (c.isDirectory) {
                if (!isSdrName(name)) walk(c, "$prefix$name/")
            } else if (c.isFile) {
                out += BookEntry("$prefix$name", c.length(), c.lastModified())
            }
        }
    }
    walk(homeDir, "")
    return out.sortedBy { it.path }
}

/**
 * One daily KOReader snapshot zip. Entries:
 * - `koreader/<path under koreaderRoot>` for the files [isSnapshotKoreaderPath] accepts
 *   (the stats DB comes with its `-wal`/`-shm`, since they live in `settings/` too);
 * - `books/<rel>.sdr/...` for every `.sdr` folder under the books home dir;
 * - [MANIFEST_ENTRY]: `[{path,size,mtime}]` of [scanBooks].
 */
object KoreaderSnapshot {
    /** Writes the snapshot to [out] (replacing it) and returns the books manifest written into it. */
    fun build(koreaderRoot: File, homeDir: File, out: File): List<BookEntry> {
        val manifest = scanBooks(homeDir)
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((rel, f) in koreaderFiles(koreaderRoot)) addFile(zip, "$KOREADER_PREFIX$rel", f)
                for ((rel, f) in sdrFiles(homeDir)) addFile(zip, "$BOOKS_PREFIX$rel", f)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return manifest
    }

    private fun koreaderFiles(root: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            for (c in (dir.listFiles() ?: return).sortedBy { it.name }) {
                val rel = prefix + c.name
                if (c.isDirectory) walk(c, "$rel/")
                else if (c.isFile && isSnapshotKoreaderPath(rel)) out += rel to c
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
            for (c in (dir.listFiles() ?: return).sortedBy { it.name }) {
                if (c.isDirectory) all(c, "$prefix${c.name}/") else if (c.isFile) out += "$prefix${c.name}" to c
            }
        }
        fun walk(dir: File, prefix: String) {
            for (c in (dir.listFiles() ?: return).sortedBy { it.name }) {
                if (c.name.startsWith(".") || !c.isDirectory) continue
                if (isSdrName(c.name)) all(c, "$prefix${c.name}/") else walk(c, "$prefix${c.name}/")
            }
        }
        walk(home, "")
        return out
    }

    private fun addFile(zip: ZipOutputStream, name: String, f: File) {
        // A file can vanish while we walk (KOReader rotating *.old); skip it instead of failing the day.
        val input = try { f.inputStream() } catch (e: FileNotFoundException) { return }
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

internal fun isSdrName(name: String) = name.endsWith(".sdr", ignoreCase = true)

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
