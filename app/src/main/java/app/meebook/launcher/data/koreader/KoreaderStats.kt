package app.meebook.launcher.data.koreader

import android.database.sqlite.SQLiteDatabase
import app.meebook.launcher.domain.DayStat
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
    fun daily(dbFile: File, zone: ZoneId, cacheDir: File): List<DayStat> {
        if (!dbFile.isFile) return emptyList()
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
            val pages = HashMap<LocalDate, HashSet<Pair<Long, Long>>>()
            val secs = HashMap<LocalDate, Long>()
            val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT id_book, page, start_time, duration FROM page_stat_data", null).use { c ->
                    while (c.moveToNext()) {
                        val day = Instant.ofEpochSecond(c.getLong(2)).atZone(zone).toLocalDate()
                        pages.getOrPut(day) { HashSet() }.add(c.getLong(0) to c.getLong(1))
                        secs.merge(day, c.getLong(3).coerceAtLeast(0), Long::plus)
                    }
                }
            } finally {
                db.close()
            }
            secs.keys.sorted().map { DayStat(it, pages[it]?.size ?: 0, secs.getValue(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            copy.delete()
            extras.forEach { File(cacheDir, copy.name + it).delete() }
        }
    }
}
