package app.nekoshelf.launcher.data.koreader

import android.database.sqlite.SQLiteDatabase
import app.nekoshelf.launcher.domain.DayStat
import app.nekoshelf.launcher.domain.ReadSpeed
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object KoreaderStats {
    /**
     * Daily stats from KOReader's statistics.sqlite3. The DB (+ -wal/-shm) is copied into
     * [cacheDir] and the copy opened read-only, since KOReader may be writing concurrently.
     * Pages = distinct (id_book, page) per day; seconds = sum(duration); days bucketed by
     * start_time in [zone]. Any failure (missing file, corrupt DB) -> empty list.
     */
    fun daily(dbFile: File, zone: ZoneId, cacheDir: File): List<DayStat> =
        withCopy(dbFile, cacheDir) { db ->
            val pages = HashMap<LocalDate, HashSet<Pair<Long, Long>>>()
            val secs = HashMap<LocalDate, Long>()
            db.rawQuery("SELECT id_book, page, start_time, duration FROM page_stat_data", null).use { c ->
                while (c.moveToNext()) {
                    val day = Instant.ofEpochSecond(c.getLong(2)).atZone(zone).toLocalDate()
                    pages.getOrPut(day) { HashSet() }.add(c.getLong(0) to c.getLong(1))
                    secs.merge(day, c.getLong(3).coerceAtLeast(0), Long::plus)
                }
            }
            secs.keys.sorted().map { DayStat(it, pages[it]?.size ?: 0, secs.getValue(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        } ?: emptyList()

    /**
     * Total reading time and pages per book, keyed by `book.md5` (the sidecar's
     * `partial_md5_checksum`). Any failure -> empty map.
     */
    fun speeds(dbFile: File, cacheDir: File): Map<String, ReadSpeed> =
        withCopy(dbFile, cacheDir) { db ->
            val out = HashMap<String, ReadSpeed>()
            db.rawQuery("SELECT md5, total_read_time, total_read_pages FROM book WHERE md5 IS NOT NULL", null).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = ReadSpeed(c.getLong(1), c.getLong(2))
            }
            out
        } ?: emptyMap()

    /** Copies the DB (+ -wal/-shm/-journal) into [cacheDir], runs [block] on the read-only copy; null on any failure. */
    private fun <T> withCopy(dbFile: File, cacheDir: File, block: (SQLiteDatabase) -> T): T? {
        if (!dbFile.isFile) return null
        val copy = File(cacheDir, "koreader-stats-copy.sqlite3")
        val extras = listOf("-wal", "-shm", "-journal")
        return try {
            cacheDir.mkdirs()
            copy.delete()
            extras.forEach { File(cacheDir, copy.name + it).delete() }
            dbFile.copyTo(copy, overwrite = true)
            for (e in extras) {
                val src = File(dbFile.path + e)
                if (src.isFile) src.copyTo(File(cacheDir, copy.name + e), overwrite = true)
            }
            val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                block(db)
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            copy.delete()
            extras.forEach { File(cacheDir, copy.name + it).delete() }
        }
    }
}
