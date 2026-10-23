package app.meebook.launcher.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** One file to bring back: from the snapshot zip ([zipEntry]) or downloaded from Drive ([book]). */
data class RestoreItem(val zipEntry: String?, val book: BookEntry?, val target: File)

/**
 * What a restore would write. [books] = book files to download, [sdr] = whole `.sdr` folders,
 * [statsRestored] = the stats DB comes back. These are exact counts of [items] for the UI.
 */
data class RestorePlan(val items: List<RestoreItem>, val books: Int, val sdr: Int, val statsRestored: Boolean)

/**
 * Only what is missing on the reader (spec "Restore"; nothing that exists is ever replaced):
 * - a book: in the snapshot's manifest and nothing at that path now;
 * - a `.sdr` folder: the reader has no folder at that path at all (even an empty one counts);
 * - a KOReader file: it does not exist, and no `-wal`/`-shm`/`-journal` of it exists either
 *   (they would pair with the restored file);
 * - `settings/statistics.sqlite3` (a single consolidated file in the snapshot): as above, or the
 *   local DB is readable and has 0 rows in `page_stat_data` (a 0-byte file counts as empty; an
 *   unreadable DB or one without that table is kept). Stats `-wal`/`-shm`/`-journal` entries
 *   are never restored;
 * - a `-wal`/`-journal` of another SQLite file: only together with that file, never on its own;
 *   `-shm` never (SQLite rebuilds it). Entries that could land outside the target dirs are dropped.
 */
fun planRestore(zip: File, koreaderRoot: File, homeDir: File): RestorePlan {
    val manifest = readManifest(zip)
    val names = ZipFile(zip).use { z -> z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSortedSet() }

    val koItems = ArrayList<RestoreItem>()
    val restoredBases = HashSet<String>()
    // Sorted, so a file always comes before its "-wal"/"-journal".
    for (name in names) {
        if (!name.startsWith(KOREADER_PREFIX)) continue
        val rel = name.removePrefix(KOREADER_PREFIX)
        if (!isSnapshotKoreaderPath(rel) || isStatsSibling(rel)) continue
        val target = resolveInside(koreaderRoot, rel) ?: continue
        val base = sqliteBaseOf(rel)
        if (base != null) {
            if (base in restoredBases && !rel.endsWith("-shm") && !target.exists()) koItems += RestoreItem(name, null, target)
        } else if (koreaderFileRestorable(rel, target)) {
            koItems += RestoreItem(name, null, target)
            restoredBases += rel
        }
    }

    val sdrGroups = LinkedHashMap<String, MutableList<RestoreItem>>()
    for (name in names) {
        if (!name.startsWith(BOOKS_PREFIX)) continue
        val rel = name.removePrefix(BOOKS_PREFIX)
        val root = sdrRootOf(rel) ?: continue
        val target = resolveInside(homeDir, rel) ?: continue
        sdrGroups.getOrPut(root) { ArrayList() } += RestoreItem(name, null, target)
    }
    val sdrRestored = sdrGroups.filterKeys { root -> resolveInside(homeDir, root)?.exists() == false }

    val bookItems = manifest.distinctBy { it.path }.sortedBy { it.path }.mapNotNull { b ->
        val target = resolveInside(homeDir, b.path) ?: return@mapNotNull null
        if (target.exists()) null else RestoreItem(null, b, target)
    }

    return RestorePlan(
        items = koItems + sdrRestored.values.flatten() + bookItems,
        books = bookItems.size,
        sdr = sdrRestored.size,
        statsRestored = STATS_REL in restoredBases,
    )
}

/**
 * Writes [plan]'s items, never over anything that exists:
 * - first removes `.restore-*.tmp` leftovers of a killed earlier run from the folders it touches;
 * - a KOReader file goes through a temp file in its folder, renamed in only if the target is
 *   still missing (the stats DB: still empty) and no `-wal`/`-shm`/`-journal` sits next to it;
 *   a `-wal`/`-journal` only if its own file was just written;
 * - a `.sdr` folder is extracted whole into a temp folder next to it and renamed in as one unit,
 *   only if no folder of that name exists by then — a failure leaves nothing behind, so the next
 *   plan still offers it;
 * - a book is fetched by [downloadBook] into the temp file it is given and placed only if it has
 *   the manifest's size (otherwise [IOException]).
 * A unit that fails is skipped and the rest still come back; afterwards one
 * [RestoreFailedException] says how many failed. Running it again only fills what is still missing.
 */
fun applyRestore(zip: File, plan: RestorePlan, downloadBook: (BookEntry, File) -> Unit) {
    val koItems = ArrayList<RestoreItem>()
    val bookItems = ArrayList<RestoreItem>()
    val sdrGroups = LinkedHashMap<File, MutableList<Pair<String, String>>>() // .sdr dir -> (entry, path inside it)
    for (item in plan.items) {
        val entry = item.zipEntry
        when {
            item.book != null -> bookItems += item
            entry == null -> Unit
            entry.startsWith(KOREADER_PREFIX) -> koItems += item
            entry.startsWith(BOOKS_PREFIX) -> {
                val rel = entry.removePrefix(BOOKS_PREFIX)
                val root = sdrRootOf(rel) ?: continue
                val depth = rel.count { it == '/' } - root.count { it == '/' }
                val sdrDir = generateSequence(item.target) { it.parentFile }.elementAtOrNull(depth) ?: continue
                sdrGroups.getOrPut(sdrDir) { ArrayList() } += entry to rel.removePrefix("$root/")
            }
        }
    }

    (koItems.map { it.target.parentFile } + sdrGroups.keys.map { it.parentFile } + bookItems.map { it.target.parentFile })
        .filterNotNull().toSet().forEach(::removeStaleTemps)

    val failures = ArrayList<Exception>()
    // One failing item (bad entry, failed download) must not keep the rest from coming back.
    fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failures += e
        }
    }

    ZipFile(zip).use { z ->
        val writtenBases = HashSet<String>()
        for (item in koItems) attempt {
            val entry = item.zipEntry!!
            val target = item.target
            val rel = entry.removePrefix(KOREADER_PREFIX)
            if (isStatsSibling(rel)) return@attempt
            val base = sqliteBaseOf(rel)
            val fill = { tmp: File -> copyEntry(z, entry, tmp) }
            if (base != null) {
                if (rel.endsWith("-shm") || base !in writtenBases || target.exists()) return@attempt
                place(target, fill)
            } else {
                if (!koreaderFileRestorable(rel, target)) return@attempt
                val placed = place(
                    target, fill,
                    precondition = { siblingsAbsent(target) },
                    mayReplace = { rel == STATS_REL && isEmptyStatsDb(target) },
                )
                if (placed) writtenBases += rel
            }
        }

        for ((sdrDir, files) in sdrGroups) attempt {
            if (sdrDir.exists()) return@attempt
            placeDir(sdrDir) { tmpDir ->
                for ((entry, inner) in files) {
                    val f = resolveInside(tmpDir, inner) ?: throw IOException("unsafe entry $entry")
                    f.parentFile!!.mkdirs()
                    copyEntry(z, entry, f)
                }
            }
        }

        for (item in bookItems) attempt {
            val book = item.book!!
            if (item.target.exists()) return@attempt
            place(item.target, fill = { tmp ->
                downloadBook(book, tmp)
                val got = if (tmp.isFile) tmp.length() else -1L
                if (got != book.size) throw IOException("${book.path}: downloaded $got bytes, expected ${book.size}")
            })
        }
    }

    if (failures.isNotEmpty()) {
        throw RestoreFailedException(failures.size, failures).apply { failures.drop(1).forEach(::addSuppressed) }
    }
}

/** [failed] restore units (a file, a whole `.sdr` folder or a book) could not be written; the rest were. */
class RestoreFailedException(val failed: Int, val causes: List<Exception>) :
    IOException("$failed restore item(s) failed; first: ${causes.firstOrNull()?.message}", causes.firstOrNull())

/** "x-wal" / "x-shm" / "x-journal" -> "x"; anything else -> null. */
private fun sqliteBaseOf(rel: String): String? =
    SQLITE_SIBLINGS.firstOrNull { rel.endsWith(it) }?.let { rel.dropLast(it.length) }?.takeIf { it.isNotEmpty() }

/** "a/b.sdr/c/d.lua" -> "a/b.sdr"; null when [rel] is not inside a `.sdr` folder. */
private fun sdrRootOf(rel: String): String? {
    val parts = rel.split('/')
    val i = parts.indexOfFirst { isSdrName(it) }
    return if (i < 0 || i == parts.lastIndex) null else parts.take(i + 1).joinToString("/")
}

private fun siblingsAbsent(target: File) = SQLITE_SIBLINGS.none { File(target.path + it).exists() }

private fun koreaderFileRestorable(rel: String, target: File): Boolean = when {
    !siblingsAbsent(target) -> false
    !target.exists() -> true
    else -> rel == STATS_REL && isEmptyStatsDb(target)
}

/**
 * True only for a stats DB with nothing to lose: 0 bytes, or readable with 0 rows in
 * `page_stat_data`. Checked on a copy (Android deletes DBs it finds corrupt).
 */
internal fun isEmptyStatsDb(f: File): Boolean {
    if (!f.isFile) return false
    if (f.length() == 0L) return true
    val copy = File.createTempFile("stats-check", ".sqlite3")
    return try {
        f.copyTo(copy, overwrite = true)
        val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            db.rawQuery("SELECT count(*) FROM page_stat_data", null).use { c -> c.moveToFirst() && c.getLong(0) == 0L }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        false
    } finally {
        copy.delete()
        SQLITE_SIBLINGS.forEach { File(copy.path + it).delete() }
    }
}

private fun copyEntry(z: ZipFile, name: String, dest: File) {
    val e = z.getEntry(name) ?: throw IOException("$name missing in snapshot")
    z.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
}

private const val TEMP_PREFIX = ".restore-"
private const val TEMP_SUFFIX = ".tmp"

/**
 * Name of a `.sdr` temp folder: short and independent of the target's name, so a long
 * (Cyrillic: 2 bytes/char) book title can never push it past the 255-byte name limit.
 */
internal fun restoreTempDirName(): String =
    "$TEMP_PREFIX${System.nanoTime()}-${(Math.random() * 1_000_000).toInt()}$TEMP_SUFFIX"

/** Deletes temp files/folders a killed restore left in [dir] (only our own `.restore-*.tmp` names). */
private fun removeStaleTemps(dir: File) {
    dir.listFiles()?.forEach { if (it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(TEMP_SUFFIX)) it.deleteRecursively() }
}

/**
 * Fills a temp file next to [target], then renames it in. Right before the rename: [precondition]
 * must hold, and an existing target is replaced only if [mayReplace] says so (otherwise the
 * rename refuses to overwrite). Returns whether the file was placed; the temp file never stays.
 */
private fun place(
    target: File,
    fill: (File) -> Unit,
    precondition: () -> Boolean = { true },
    mayReplace: () -> Boolean = { false },
): Boolean {
    val dir = target.parentFile ?: throw IOException("no parent for $target")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
    val tmp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, dir)
    try {
        fill(tmp)
        if (!precondition()) return false
        if (target.exists()) {
            if (!mayReplace()) return false
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            try {
                Files.move(tmp.toPath(), target.toPath()) // no REPLACE_EXISTING: fails if it appeared
            } catch (e: FileAlreadyExistsException) {
                return false
            }
        }
        return true
    } finally {
        tmp.delete()
    }
}

/**
 * Fills a temp folder next to [target] and renames the whole folder in, only if nothing exists
 * at [target] by then. On any failure the temp folder is removed and [target] never appears.
 */
private fun placeDir(target: File, fill: (File) -> Unit): Boolean {
    val dir = target.parentFile ?: throw IOException("no parent for $target")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
    val tmp = generateSequence { File(dir, restoreTempDirName()) }.take(5).firstOrNull { it.mkdir() }
        ?: throw IOException("cannot create a temp folder in $dir")
    try {
        fill(tmp)
        if (target.exists()) return false
        try {
            Files.move(tmp.toPath(), target.toPath()) // no REPLACE_EXISTING: fails if it appeared
        } catch (e: FileAlreadyExistsException) {
            return false
        }
        return true
    } finally {
        if (tmp.exists()) tmp.deleteRecursively()
    }
}
