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
 */
class HomeLoader(
    context: Context,
    private val koreaderRoot: File = File(Environment.getExternalStorageDirectory(), "koreader"),
) {
    private val appContext = context.applicationContext ?: context
    private val cacheDir = appContext.cacheDir
    val bridge = MangaBridge(appContext)
    private val bookCovers = BookCovers(File(cacheDir, "covers"), COVER_PX)
    private val mangaCovers = MangaCovers(cacheDir) { bridge.cover(it) }

    fun load(): HomeData {
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
        val bookDays = KoreaderStats.daily(bookDb, zone, cacheDir)
        val t1 = SystemClock.elapsedRealtime()

        // available() first: without «Манга» every bridge call would cost a 300 ms retry.
        val mangaAvailable = bridge.available()
        var manga: List<Manga> = emptyList()
        var mangaDays: List<DayStat> = emptyList()
        var mangaStats: StatsSummary? = null
        if (mangaAvailable) {
            manga = bridge.library()
            mangaDays = bridge.daily()
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
        )
    }

    companion object {
        private const val TAG = "HomeLoader"
        /** Cover extraction height in px: about the «Сейчас читаю» cover at density 2; shelf covers are smaller. */
        const val COVER_PX = 240
    }
}
