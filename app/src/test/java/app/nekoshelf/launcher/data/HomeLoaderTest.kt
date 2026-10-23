package app.nekoshelf.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import app.nekoshelf.launcher.data.manga.FakeMangaProvider
import app.nekoshelf.launcher.domain.StatsCalc
import org.junit.Assert.assertNull
import org.robolectric.Robolectric
import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.nekoshelf.launcher.data.koreader.KoreaderStats
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
    fun mangaInstalledWithoutPermissionIsInstalledButUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = HomeLoader(context, koreaderRoot(pages = 1))
        assertFalse(loader.load().mangaInstalled)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "app.nekoshelf.manga" })
        val data = loader.load()
        assertTrue(data.mangaInstalled)
        assertFalse(data.mangaAvailable)
    }

    /** «Манга» installed, permitted and answered by [FakeMangaProvider]. */
    private fun withManga(context: Context) {
        FakeMangaProvider.reset()
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "app.nekoshelf.manga" })
        shadowOf(context as android.app.Application).grantPermissions("app.nekoshelf.manga.permission.BRIDGE")
        val info = ProviderInfo().apply {
            authority = "app.nekoshelf.manga.bridge"
            packageName = "app.nekoshelf.manga"
            name = FakeMangaProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(FakeMangaProvider::class.java).create(info)
    }

    @Test
    fun booksPhaseIsDeliveredBeforeASlowMangaAndMangaMergesLater() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        withManga(context)
        val gate = CountDownLatch(1)
        FakeMangaProvider.gate = gate // «Манга» cold: the provider doesn't answer yet
        val loader = HomeLoader(context, koreaderRoot(pages = 7))
        val booksPhase = ArrayBlockingQueue<HomeData>(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val full = pool.submit<HomeData> { loader.load(onBooks = { booksPhase.add(it) }) }
            val books = booksPhase.poll(20, TimeUnit.SECONDS)
            assertNotNull("books phase must arrive while «Манга» is still blocked", books)
            assertFalse(full.isDone)
            assertTrue(books!!.loaded)
            assertEquals(7L, books.bookStats!!.pagesTotal)
            assertFalse("first load: no manga read yet", books.mangaLoaded)
            assertTrue(books.manga.isEmpty())
            assertNull(books.mangaStats)

            gate.countDown()
            val merged = full.get(30, TimeUnit.SECONDS)
            assertTrue(merged.mangaLoaded)
            assertTrue(merged.mangaAvailable)
            assertEquals(listOf("Berserk", "Vagabond"), merged.manga.map { it.title })
            assertEquals("Berserk", merged.nowManga?.title)
            assertNotNull(merged.mangaStats)
            // The book side of the merged result is the books phase, unchanged.
            assertEquals(books.copy(
                manga = merged.manga, mangaAvailable = true, mangaInstalled = true, mangaStats = merged.mangaStats,
                week = merged.week, nowManga = merged.nowManga, mangaCovers = merged.mangaCovers, mangaLoaded = true,
                mangaDays = merged.mangaDays, mangaBackfilled = merged.mangaBackfilled,
            ), merged)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun theMergedResultEqualsASingleLoadAndTheNextBooksPhaseKeepsTheLastManga() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        withManga(context)
        val loader = HomeLoader(context, koreaderRoot(pages = 3))
        val single = loader.load()
        var books: HomeData? = null
        val merged = loader.load(onBooks = { books = it })
        // Same inputs: the two-phase result equals the plain load (bar the evaluation clock).
        assertEquals(single.copy(nowMs = 0), merged.copy(nowMs = 0))
        assertEquals(StatsCalc.week(merged.bookDays, merged.mangaDays, merged.today), merged.week)
        // A later books phase shows the previous manga read instead of blanking it.
        assertTrue(books!!.mangaLoaded)
        assertEquals(single.manga, books!!.manga)
        assertEquals(single.mangaStats, books!!.mangaStats)
        assertEquals(merged.copy(nowMs = 0), books!!.copy(nowMs = 0))
    }

    @Test
    fun statsAreReReadOnlyWhenTheDbChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = koreaderRoot(pages = 5)
        val reads = AtomicInteger(0)
        val loader = HomeLoader(context, root) { db, zone, cache -> reads.incrementAndGet(); KoreaderStats.daily(db, zone, cache) }
        assertEquals(5L, loader.load().bookStats!!.pagesTotal)
        assertEquals(5L, loader.load().bookStats!!.pagesTotal)
        assertEquals(1, reads.get())
        // KOReader writes a page: the DB changes, so the next load reads it again.
        val dbFile = File(root, "settings/statistics.sqlite3")
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO page_stat_data VALUES (1, 99, ?, 30, 1000)", arrayOf<Any>(System.currentTimeMillis() / 1000))
        }
        dbFile.setLastModified(dbFile.lastModified() + 2_000)
        assertEquals(6L, loader.load().bookStats!!.pagesTotal)
        assertEquals(2, reads.get())
    }

    @Test
    fun timeLeftIsComputedAndSpeedsAreReadOnlyWhenTheDbChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = koreaderRoot(pages = 5)
        val books = tmp.newFolder("Books")
        File(root, "settings.reader.lua").writeText("""return { ["home_dir"] = "${books.path.replace("\\", "/")}" }""")
        val book = File(books, "a.epub").apply { writeText("x") }
        val sdr = File(books, "a.sdr").apply { mkdirs() }
        File(sdr, "metadata.epub.lua").writeText(
            """return { ["partial_md5_checksum"] = "abc", ["doc_pages"] = 200, ["percent_finished"] = 0.5, ["summary"] = { ["status"] = "reading" } }"""
        )
        File(root, "history.lua").writeText("""return { [1] = { ["time"] = 100, ["file"] = "${book.path.replace("\\", "/")}" } }""")
        val dbFile = File(root, "settings/statistics.sqlite3")
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TABLE book (id integer PRIMARY KEY, md5 text, total_read_time integer, total_read_pages integer)")
            it.execSQL("INSERT INTO book VALUES (1, 'abc', 600, 20)")
        }
        val reads = AtomicInteger(0)
        val loader = HomeLoader(context, root, readSpeeds = { db, cache -> reads.incrementAndGet(); KoreaderStats.speeds(db, cache) })
        assertEquals(100L * 30, loader.load().nowBookSecondsLeft)
        assertEquals(100L * 30, loader.load().nowBookSecondsLeft)
        assertEquals(1, reads.get())
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE book SET total_read_time = 1200 WHERE id = 1")
        }
        dbFile.setLastModified(dbFile.lastModified() + 2_000)
        assertEquals(100L * 60, loader.load().nowBookSecondsLeft)
        assertEquals(2, reads.get())
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
