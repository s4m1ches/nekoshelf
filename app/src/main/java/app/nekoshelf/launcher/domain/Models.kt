package app.nekoshelf.launcher.domain

import app.nekoshelf.launcher.data.books.BookFile
import app.nekoshelf.launcher.data.books.BookMeta
import app.nekoshelf.launcher.data.koreader.BookState
import java.io.File
import java.time.LocalDate

/** Reading activity for one device-local calendar day. */
data class DayStat(val day: LocalDate, val pages: Int, val seconds: Int)

data class StatsSummary(
    val pagesToday: Int,
    val pagesTotal: Long,
    val secondsToday: Int,
    val secondsTotal: Long,
)

/**
 * A book on the shelf. [lastOpened] is epoch seconds (KOReader history), [addedAt] is ms.
 * [series] is the metadata series, falling back to the folder series.
 */
data class Book(
    val file: File,
    val title: String,
    val authors: String?,
    val series: String?,
    val seriesIndex: Double?,
    val percent: Double?,
    val status: String?,
    val lastOpened: Long?,
    val addedAt: Long,
) {
    companion object {
        /**
         * Builds a [Book]: title = sidecar title ?: the title inside the file ([meta]) ?: file name
         * without extension, authors likewise; series = sidecar ?: folder.
         */
        fun from(bookFile: BookFile, state: BookState?, lastOpenedSeconds: Long?, meta: BookMeta? = null): Book = Book(
            file = bookFile.file,
            title = state?.title?.takeIf { it.isNotBlank() } ?: meta?.title?.takeIf { it.isNotBlank() } ?: baseName(bookFile.file),
            authors = state?.authors?.takeIf { it.isNotBlank() } ?: meta?.authors?.takeIf { it.isNotBlank() },
            series = state?.series?.takeIf { it.isNotBlank() } ?: bookFile.folderSeries,
            seriesIndex = state?.seriesIndex,
            percent = state?.percent,
            status = state?.status,
            lastOpened = lastOpenedSeconds,
            addedAt = bookFile.addedAt,
        )

        private fun baseName(f: File): String {
            val name = f.name
            return if (name.endsWith(".fb2.zip", ignoreCase = true)) name.dropLast(".fb2.zip".length) else f.nameWithoutExtension
        }
    }
}

/** A «Манга» library title. All times are epoch ms; [lastReadAt] is 0 when it has no history. */
data class Manga(
    val id: Long,
    val title: String,
    val lastReadAt: Long,
    val chaptersTotal: Int,
    val chaptersRead: Int,
    val nextChapterName: String?,
    val dateAdded: Long,
    val coverVersion: Long,
    /** Chapters fetched after the last read; 0 also when an older «Манга» doesn't report it. */
    val chaptersNew: Int = 0,
)

/** One top-level unit on the shelf: a cover (asCover = true) or a spine. */
sealed interface ShelfItem

data class BookItem(val book: Book, val asCover: Boolean) : ShelfItem

data class MangaItem(val manga: Manga, val asCover: Boolean, val caughtUp: Boolean) : ShelfItem

/** Volumes of one series in volume order; in-progress volumes are covers. */
data class SeriesGroup(val name: String, val items: List<BookItem>) : ShelfItem

enum class ShelfTab { BOOKS, MANGA, ALL }

/** Seconds of books and of manga: one month of a year, or one weekday's average. */
data class Split(val bookSeconds: Long, val mangaSeconds: Long)

/**
 * A year at a glance, books and manga together. [currentStreak] is 0 unless the year is the
 * current one; [weekdays] are average seconds per weekday, Monday first.
 */
data class YearOverview(
    val seconds: Long,
    val readingDays: Int,
    val bestStreak: Int,
    val currentStreak: Int,
    val months: List<Split>,
    val weekdays: List<Split>,
)
