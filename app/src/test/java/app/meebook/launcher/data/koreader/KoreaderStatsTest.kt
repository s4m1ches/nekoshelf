package app.meebook.launcher.data.koreader

import android.database.sqlite.SQLiteDatabase
import app.meebook.launcher.domain.DayStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class KoreaderStatsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val aqtau = ZoneId.of("Asia/Aqtau")

    private fun epoch(iso: String) = OffsetDateTime.parse(iso).toEpochSecond()

    private fun makeDb(rows: List<Triple<Int, Long, Int>>): File {
        val f = File(tmp.root, "statistics.sqlite3")
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE book (id integer PRIMARY KEY autoincrement, title text, authors text, notes integer, last_open integer, highlights integer, pages integer, series text, language text, md5 text, total_read_time integer, total_read_pages integer)")
        db.execSQL("CREATE TABLE page_stat_data (id_book integer, page integer NOT NULL DEFAULT 0, start_time integer NOT NULL DEFAULT 0, duration integer NOT NULL DEFAULT 0, total_pages integer NOT NULL DEFAULT 0, UNIQUE (id_book, page, start_time), FOREIGN KEY(id_book) REFERENCES book(id))")
        db.execSQL("INSERT INTO book(id, title) VALUES (1, 'A')")
        for ((page, start, dur) in rows) {
            db.execSQL("INSERT INTO page_stat_data(id_book, page, start_time, duration, total_pages) VALUES (1, ?, ?, ?, 100)", arrayOf<Any>(page, start, dur))
        }
        db.close()
        return f
    }

    private fun cache() = tmp.newFolder("cache")

    @Test fun midnightSplitsIntoTwoLocalDays() {
        val db = makeDb(listOf(
            Triple(1, epoch("2026-10-22T23:59:00+05:00"), 30),
            Triple(2, epoch("2026-10-23T00:01:00+05:00"), 40),
        ))
        val r = KoreaderStats.daily(db, aqtau, cache())
        assertEquals(
            listOf(
                DayStat(LocalDate.of(2026, 10, 22), 1, 30),
                DayStat(LocalDate.of(2026, 10, 23), 1, 40),
            ), r
        )
    }

    @Test fun samePageTwiceCountsOnceSecondsAdd() {
        val t = epoch("2026-10-23T10:00:00+05:00")
        val db = makeDb(listOf(Triple(5, t, 20), Triple(5, t + 600, 15), Triple(6, t + 700, 5)))
        val r = KoreaderStats.daily(db, aqtau, cache())
        assertEquals(listOf(DayStat(LocalDate.of(2026, 10, 23), 2, 40)), r)
    }

    @Test fun missingDbIsEmpty() {
        assertTrue(KoreaderStats.daily(File(tmp.root, "nope.sqlite3"), aqtau, cache()).isEmpty())
    }

    @Test fun garbageDbIsEmptyNotThrow() {
        val f = tmp.newFile("bad.sqlite3").also { it.writeText("not a database") }
        assertTrue(KoreaderStats.daily(f, aqtau, cache()).isEmpty())
    }

    @Test fun usesCopyAndLeavesOriginalUntouched() {
        val db = makeDb(listOf(Triple(1, epoch("2026-10-23T10:00:00+05:00"), 10)))
        val before = db.length()
        KoreaderStats.daily(db, aqtau, cache())
        assertEquals(before, db.length())
    }
}
