package app.meebook.launcher.data

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import app.meebook.launcher.data.books.BookCovers
import app.meebook.launcher.data.books.BookScanner
import app.meebook.launcher.data.koreader.History
import app.meebook.launcher.data.koreader.KoreaderPaths
import app.meebook.launcher.data.koreader.KoreaderStats
import app.meebook.launcher.data.koreader.Sidecar
import app.meebook.launcher.data.manga.MangaBridge
import app.meebook.launcher.data.manga.MangaCovers
import app.meebook.launcher.domain.Book
import app.meebook.launcher.domain.DayStat
import app.meebook.launcher.domain.Manga
import app.meebook.launcher.domain.ShelfBuilder
import app.meebook.launcher.domain.StatsCalc
import app.meebook.launcher.domain.StatsSummary
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Everything the home screen shows below the header, loaded in one pass. */
data class HomeData(
    val books: List<Book>,
    val manga: List<Manga>,
    val mangaAvailable: Boolean,
    /** «Манга» is installed; with ![mangaAvailable] that means the BRIDGE permission is missing. */
    val mangaInstalled: Boolean = mangaAvailable,
    /** null when KOReader has no statistics database. */
    val bookStats: StatsSummary?,
    /** null when «Манга» is not available. */
    val mangaStats: StatsSummary?,
    /** Monday..Sunday minutes, books + manga. */
    val week: List<Pair<LocalDate, Int>>,
    val today: LocalDate,
    val nowBook: Book?,
    val nowManga: Manga?,
    val bookCovers: Map<File, File>,
    val mangaCovers: Map<Long, File>,
    /** Wall-clock ms the shelf rules are evaluated at. */
    val nowMs: Long,
    val loaded: Boolean,
    /** Per-day reading for the year heatmaps. */
    val bookDays: List<DayStat> = emptyList(),
    val mangaDays: List<DayStat> = emptyList(),
    /** Some «Манга» days come from the history import (seconds known, no pages). */
    val mangaBackfilled: Boolean = false,
) {
    companion object {
        fun empty(): HomeData = HomeData(
            books = emptyList(), manga = emptyList(), mangaAvailable = false, bookStats = null,
            mangaStats = null, week = emptyList(), today = LocalDate.now(), nowBook = null, nowManga = null,
            bookCovers = emptyMap(), mangaCovers = emptyMap(), nowMs = System.currentTimeMillis(), loaded = false,
        )
    }
}

/**
 * Reads KOReader's files, the book folder and the «Манга» bridge into a [HomeData]. Blocking:
 * call on Dispatchers.IO, once per refresh (the stats DB is copied on every call). Each source
 * degrades to empty on its own: missing KOReader data never hides manga and vice versa.
 *
 * Loads are serialized process-wide: a cancelled refresh's load keeps running on IO while the
 * next one starts, and both would share the fixed stats-copy file in cacheDir (and the cover
 * cache files) — one could delete the other's copy mid-read and come back empty.
 */
class HomeLoader(
    context: Context,
    private val koreaderRoot: File = File(Environment.getExternalStorageDirectory(), "koreader"),
    /** KOReader stats reader; replaceable in tests. */
    private val readStats: (db: File, zone: ZoneId, cacheDir: File) -> List<DayStat> = KoreaderStats::daily,
) {
    private val appContext = context.applicationContext ?: context
    private val cacheDir = appContext.cacheDir
    val bridge = MangaBridge(appContext)
    private val bookCovers = BookCovers(File(cacheDir, "covers"), COVER_PX)
    private val mangaCovers = MangaCovers(cacheDir) { bridge.cover(it) }
    /** Last KOReader stats read, keyed on the DB files' size + mtime (and the zone). Under [LOCK]. */
    private var statsMemo: Pair<List<Any>, List<DayStat>>? = null

    fun load(): HomeData = synchronized(LOCK) { loadLocked() }

    private fun loadLocked(): HomeData {
        val t0 = SystemClock.elapsedRealtime()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowMs = System.currentTimeMillis()

        val homeDir = KoreaderPaths(koreaderRoot).homeDir()
        val opened = History.read(koreaderRoot).associate { it.file.absolutePath to it.time }
        val books = BookScanner.scan(homeDir).map { bf ->
            Book.from(bf, Sidecar.read(bf.file), opened[bf.file.absolutePath])
        }
        val bookDb = File(koreaderRoot, "settings/statistics.sqlite3")
        val bookDays = bookDays(bookDb, zone)
        val t1 = SystemClock.elapsedRealtime()

        // available() first: without «Манга» every bridge call would cost a 300 ms retry.
        val mangaInstalled = bridge.installed()
        val mangaAvailable = mangaInstalled && bridge.available()
        var manga: List<Manga> = emptyList()
        var mangaDays: List<DayStat> = emptyList()
        var mangaStats: StatsSummary? = null
        var mangaBackfilled = false
        if (mangaAvailable) {
            manga = bridge.library()
            mangaDays = bridge.daily()
            mangaBackfilled = bridge.hasBackfilledDays()
            val (pages, seconds) = bridge.totals()
            val s = StatsCalc.summary(mangaDays, today)
            mangaStats = if (pages > 0 || seconds > 0) s.copy(pagesTotal = pages, secondsTotal = seconds) else s
        }
        val t2 = SystemClock.elapsedRealtime()

        val nowBook = ShelfBuilder.nowReadingBook(books)
        val nowManga = ShelfBuilder.nowReadingManga(manga)

        // Covers only for what is drawn as a cover: in-progress items and the «Сейчас читаю» cards.
        val bookCoverMap = HashMap<File, File>()
        (books.filter { ShelfBuilder.isInProgress(it) } + listOfNotNull(nowBook)).distinct().forEach { b ->
            bookCovers.cover(b.file)?.let { bookCoverMap[b.file] = it }
        }
        val mangaCoverMap = HashMap<Long, File>()
        (manga.filter { ShelfBuilder.isInProgress(it, nowMs) } + listOfNotNull(nowManga)).distinct().forEach { m ->
            mangaCovers.cover(m.id, m.coverVersion)?.let { mangaCoverMap[m.id] = it }
        }
        val t3 = SystemClock.elapsedRealtime()
        Log.i(TAG, "load: books=${books.size} manga=${manga.size} koreader=${t1 - t0}ms bridge=${t2 - t1}ms covers=${t3 - t2}ms")

        return HomeData(
            books = books,
            manga = manga,
            mangaAvailable = mangaAvailable,
            mangaInstalled = mangaInstalled,
            bookStats = if (bookDb.isFile) StatsCalc.summary(bookDays, today) else null,
            mangaStats = mangaStats,
            week = StatsCalc.week(bookDays, mangaDays, today),
            today = today,
            nowBook = nowBook,
            nowManga = nowManga,
            bookCovers = bookCoverMap,
            mangaCovers = mangaCoverMap,
            nowMs = nowMs,
            loaded = true,
            bookDays = bookDays,
            mangaDays = mangaDays,
            mangaBackfilled = mangaBackfilled,
        )
    }

    /**
     * KOReader's daily stats, re-read (DB copied and queried) only when statistics.sqlite3 or its
     * -wal changed since the last load: most returns to home follow no reading at all.
     */
    private fun bookDays(db: File, zone: ZoneId): List<DayStat> {
        val key = listOf<Any>(zone) + listOf("", "-wal").flatMap { File(db.path + it).let { f -> listOf(f.length(), f.lastModified()) } }
        statsMemo?.let { (k, days) -> if (k == key) return days }
        // An empty read of an existing DB may be a transient failure: don't keep it.
        return readStats(db, zone, cacheDir).also { if (it.isNotEmpty() || !db.isFile) statsMemo = key to it }
    }

    companion object {
        private const val TAG = "HomeLoader"
        private val LOCK = Any()
        /** Cover extraction height in px: about the «Сейчас читаю» cover at density 2; shelf covers are smaller. */
        const val COVER_PX = 240
    }
}
