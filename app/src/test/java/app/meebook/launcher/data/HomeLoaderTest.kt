package app.meebook.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.meebook.launcher.data.koreader.KoreaderStats
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A KOReader root whose stats DB holds [pages] distinct pages read now. */
    private fun koreaderRoot(pages: Int): File {
        val root = tmp.newFolder("koreader")
        val settings = File(root, "settings").apply { mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(File(settings, "statistics.sqlite3"), null)
        db.execSQL("CREATE TABLE page_stat_data (id_book integer, page integer, start_time integer, duration integer, total_pages integer)")
        val now = System.currentTimeMillis() / 1000
        db.beginTransaction()
        for (p in 1..pages) {
            db.execSQL("INSERT INTO page_stat_data VALUES (1, ?, ?, 30, 1000)", arrayOf<Any>(p, now - p))
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        db.close()
        return root
    }

    @Test
    fun loadReturnsTheKoreaderStats() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stats = HomeLoader(context, koreaderRoot(pages = 40)).load().bookStats
        assertNotNull(stats)
        assertEquals(40L, stats!!.pagesTotal)
    }

    @Test
    fun overlappingLoadsAreSerialized() {
        // Overlapping refreshes (onStart while a cancelled load still runs on IO) would share the
        // fixed stats-copy path in cacheDir; one load could delete the other's copy mid-read.
        // The stats reader records how many loads are inside it at once: must never exceed 1.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val loader = HomeLoader(context, koreaderRoot(pages = 3)) { db, zone, cache ->
            maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(50)
                KoreaderStats.daily(db, zone, cache)
            } finally {
                inside.decrementAndGet()
            }
        }
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val results = (1..threads).map { pool.submit<HomeData> { start.await(); loader.load() } }
            start.countDown()
            for (r in results) assertEquals(3L, r.get(60, TimeUnit.SECONDS).bookStats!!.pagesTotal)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, maxInside.get())
    }
}
