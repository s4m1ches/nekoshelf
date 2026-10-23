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
import app.meebook.launcher.domain.ReadSpeed
import app.meebook.launcher.domain.ShelfBuilder
import app.meebook.launcher.domain.secondsLeft
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
    /**
     * The «Манга» fields hold a bridge read. False in the books phase of the first load: the
     * manga parts of the screen stay blank (no premature «не установлена»).
     */
    val mangaLoaded: Boolean = loaded,
    /** Per-day reading for the year heatmaps. */
    val bookDays: List<DayStat> = emptyList(),
    val mangaDays: List<DayStat> = emptyList(),
    /** Some «Манга» days come from the history import (seconds known, no pages). */
    val mangaBackfilled: Boolean = false,
    /** Estimated time to finish [nowBook]; null without enough reading statistics. */
    val nowBookSecondsLeft: Long? = null,
) {
    companion object {
        fun empty(): HomeData = HomeData(
            books = emptyList(), manga = emptyList(), mangaAvailable = false, bookStats = null,
            mangaStats = null, week = emptyList(), today = LocalDate.now(), nowBook = null, nowManga = null,
            bookCovers = emptyMap(), mangaCovers = emptyMap(), nowMs = System.currentTimeMillis(), loaded = false,
            mangaLoaded = false,
        )
    }
}

/**
 * Reads KOReader's files, the book folder and the «Манга» bridge into a [HomeData]. Blocking:
 * call on Dispatchers.IO, once per refresh (the stats DB is copied when it changed). Each source
 * degrades to empty on its own: missing KOReader data never hides manga and vice versa.
 *
 * Loads are serialized process-wide: a cancelled refresh's load keeps running on IO while the
 * next one starts, and both would share the fixed stats-copy file in cacheDir (and the cover
 * cache files) — one could delete the other's copy mid-read and come back empty.
 */
class HomeLoader(
    context: Context,
    private val koreaderRoot: File = File(Environment.getExternalStorageDirectory(), "koreader"),
    /** KOReader per-book reading speeds; replaceable in tests. */
    private val readSpeeds: (db: File, cacheDir: File) -> Map<String, ReadSpeed> = KoreaderStats::speeds,
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
    /** Last per-book speeds read, same key as [statsMemo]. Under [LOCK]. */
    private var speedsMemo: Pair<List<Any>, Map<String, ReadSpeed>>? = null

    /** The last manga phase, shown again in the next books phase until the new one lands. Under [LOCK]. */
    private var lastManga: MangaPart? = null

    /**
     * Loads everything, in two phases under one lock (loads never overlap). The books phase
     * (KOReader: books, stats, covers) is handed to [onBooks] as soon as it is read — with the
     * previous load's manga data, or none yet ([HomeData.mangaLoaded] false) on the first load —
     * so a slow «Манга» provider (seconds on its cold start) never holds the books back. Then the
     * manga phase is read from the bridge and the merged result returned. [onBooks] runs on the
     * calling thread, inside the lock: keep it short.
     */
    fun load(onBooks: (HomeData) -> Unit = {}): HomeData = synchronized(LOCK) {
        val t0 = SystemClock.elapsedRealtime()
        val books = readBooks()
        val t1 = SystemClock.elapsedRealtime()
        onBooks(merge(books, lastManga))
        val manga = readManga(books.today, books.nowMs)
        lastManga = manga
        val t2 = SystemClock.elapsedRealtime()
        Log.i(TAG, "load: books=${books.books.size} manga=${manga.manga.size} koreader=${t1 - t0}ms manga=${t2 - t1}ms")
        merge(books, manga)
    }

    /** What the KOReader side contributes. */
    internal data class BooksPart(
        val books: List<Book>,
        val bookDays: List<DayStat>,
        val hasStatsDb: Boolean,
        val nowBook: Book?,
        val covers: Map<File, File>,
        val today: LocalDate,
        val nowMs: Long,
        val nowBookSecondsLeft: Long? = null,
    )

    /** What the «Манга» bridge contributes. */
    internal data class MangaPart(
        val installed: Boolean,
        val available: Boolean,
        val manga: List<Manga>,
        val days: List<DayStat>,
        val stats: StatsSummary?,
        val backfilled: Boolean,
        val nowManga: Manga?,
        val covers: Map<Long, File>,
    )

    private fun readBooks(): BooksPart {
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
        val nowBook = ShelfBuilder.nowReadingBook(books)
        // Covers only for what is drawn as a cover: in-progress items and the «Сейчас читаю» card.
        val covers = HashMap<File, File>()
        (books.filter { ShelfBuilder.isInProgress(it) } + listOfNotNull(nowBook)).distinct().forEach { b ->
            bookCovers.cover(b.file)?.let { covers[b.file] = it }
        }
        return BooksPart(books, bookDays, bookDb.isFile, nowBook, covers, today, nowMs, nowBookSecondsLeft(nowBook, bookDb))
    }

    private fun nowBookSecondsLeft(nowBook: Book?, db: File): Long? {
        if (nowBook == null || nowBook.status == "complete") return null
        val state = Sidecar.read(nowBook.file) ?: return null
        val speeds = bookSpeeds(db)
        return secondsLeft(state.pages, state.percent, state.md5?.let { speeds[it] }, speeds.values)
    }

    private fun readManga(today: LocalDate, nowMs: Long): MangaPart {
        // available() first: without «Манга» every bridge call would cost a 300 ms retry.
        val installed = bridge.installed()
        val available = installed && bridge.available()
        if (!available) return MangaPart(installed, false, emptyList(), emptyList(), null, false, null, emptyMap())
        val manga = bridge.library()
        val days = bridge.daily()
        val backfilled = bridge.hasBackfilledDays()
        val (pages, seconds) = bridge.totals()
        val s = StatsCalc.summary(days, today)
        val stats = if (pages > 0 || seconds > 0) s.copy(pagesTotal = pages, secondsTotal = seconds) else s
        val nowManga = ShelfBuilder.nowReadingManga(manga)
        val covers = HashMap<Long, File>()
        (manga.filter { ShelfBuilder.isInProgress(it, nowMs) } + listOfNotNull(nowManga)).distinct().forEach { m ->
            mangaCovers.cover(m.id, m.coverVersion)?.let { covers[m.id] = it }
        }
        return MangaPart(installed, true, manga, days, stats, backfilled, nowManga, covers)
    }

    /** The home data for [b] plus [m]; a null [m] means the manga phase hasn't been read yet. */
    internal fun merge(b: BooksPart, m: MangaPart?): HomeData = HomeData(
        books = b.books,
        manga = m?.manga.orEmpty(),
        mangaAvailable = m?.available ?: false,
        mangaInstalled = m?.installed ?: false,
        bookStats = if (b.hasStatsDb) StatsCalc.summary(b.bookDays, b.today) else null,
        mangaStats = m?.stats,
        week = StatsCalc.week(b.bookDays, m?.days.orEmpty(), b.today),
        today = b.today,
        nowBook = b.nowBook,
        nowManga = m?.nowManga,
        bookCovers = b.covers,
        mangaCovers = m?.covers.orEmpty(),
        nowMs = b.nowMs,
        loaded = true,
        mangaLoaded = m != null,
        bookDays = b.bookDays,
        mangaDays = m?.days.orEmpty(),
        mangaBackfilled = m?.backfilled ?: false,
        nowBookSecondsLeft = b.nowBookSecondsLeft,
    )

    /**
     * KOReader's daily stats, re-read (DB copied and queried) only when statistics.sqlite3 or its
     * -wal changed since the last load: most returns to home follow no reading at all.
     */
    private fun bookDays(db: File, zone: ZoneId): List<DayStat> {
        val key = statsKey(db, zone)
        statsMemo?.let { (k, days) -> if (k == key) return days }
        // An empty read of an existing DB may be a transient failure: don't keep it.
        return readStats(db, zone, cacheDir).also { if (it.isNotEmpty() || !db.isFile) statsMemo = key to it }
    }

    private fun statsKey(db: File, zone: ZoneId?): List<Any> =
        listOfNotNull<Any>(zone) + listOf("", "-wal").flatMap { File(db.path + it).let { f -> listOf(f.length(), f.lastModified()) } }

    /** Per-book speeds, re-read only when the stats DB changed (like [bookDays]). */
    private fun bookSpeeds(db: File): Map<String, ReadSpeed> {
        val key = statsKey(db, null)
        speedsMemo?.let { (k, v) -> if (k == key) return v }
        return readSpeeds(db, cacheDir).also { if (it.isNotEmpty() || !db.isFile) speedsMemo = key to it }
    }

    companion object {
        private const val TAG = "HomeLoader"
        private val LOCK = Any()
        /** Cover extraction height in px: about the «Сейчас читаю» cover at density 2; shelf covers are smaller. */
        const val COVER_PX = 240
    }
}
