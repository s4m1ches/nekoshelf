package app.nekoshelf.launcher.domain

import app.nekoshelf.launcher.data.books.BookFile
import app.nekoshelf.launcher.data.koreader.BookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShelfBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private val now = 1_792_713_600_000L // 2026-10-23T00:00:00Z
    private fun dayN(n: Int) = now - (30 - n) * day // "day 1" .. "day 30" of the last month, ms

    private fun book(
        name: String,
        percent: Double? = null,
        status: String? = null,
        lastOpenedMs: Long? = null,
        addedAt: Long = dayN(1),
        series: String? = null,
        seriesIndex: Double? = null,
        file: File = File("/books/$name.epub"),
    ) = Book(
        file = file, title = name, authors = null, series = series, seriesIndex = seriesIndex,
        percent = percent, status = status, lastOpened = lastOpenedMs?.div(1000), addedAt = addedAt,
    )

    private fun manga(
        id: Long,
        lastReadAt: Long,
        total: Int,
        read: Int,
        dateAdded: Long = dayN(1),
    ) = Manga(id, "Манга $id", lastReadAt, total, read, null, dateAdded, 0L)

    // --- book in-progress rule ---

    @Test fun bookInProgressRule() {
        assertTrue(ShelfBuilder.isInProgress(book("x", percent = 0.5)))
        assertFalse(ShelfBuilder.isInProgress(book("x", percent = 0.5, status = "complete")))
        assertFalse(ShelfBuilder.isInProgress(book("x", percent = 0.5, status = "abandoned")))
        assertFalse(ShelfBuilder.isInProgress(book("x", percent = 1.0)))
        assertFalse(ShelfBuilder.isInProgress(book("x", percent = null)))
        assertTrue(ShelfBuilder.isInProgress(book("x", percent = null, status = "reading")))
        assertFalse(ShelfBuilder.isInProgress(book("x", percent = 0.0)))
    }

    // --- manga in-progress rule ---

    @Test fun caughtUpMangaWithin30DaysIsCoverCaughtUp() {
        val m = manga(1, lastReadAt = now - 10 * day, total = 12, read = 12)
        assertTrue(ShelfBuilder.isInProgress(m, now))
        assertEquals(listOf(MangaItem(m, asCover = true, caughtUp = true)), ShelfBuilder.build(emptyList(), listOf(m), ShelfTab.MANGA, now))
    }

    @Test fun caughtUpMangaAfter30DaysIsSpine() {
        val m = manga(1, lastReadAt = now - 31 * day, total = 12, read = 12)
        assertFalse(ShelfBuilder.isInProgress(m, now))
        assertEquals(listOf(MangaItem(m, asCover = false, caughtUp = false)), ShelfBuilder.build(emptyList(), listOf(m), ShelfTab.MANGA, now))
    }

    @Test fun unreadChaptersWithoutHistoryIsSpine() {
        val m = manga(1, lastReadAt = 0, total = 12, read = 0)
        assertFalse(ShelfBuilder.isInProgress(m, now))
        assertEquals(listOf(MangaItem(m, asCover = false, caughtUp = false)), ShelfBuilder.build(emptyList(), listOf(m), ShelfTab.MANGA, now))
    }

    @Test fun unreadChaptersWithOldHistoryIsStillInProgress() {
        val m = manga(1, lastReadAt = now - 100 * day, total = 12, read = 5)
        assertTrue(ShelfBuilder.isInProgress(m, now))
    }

    // --- ordering ---

    @Test fun inProgressFirstThenByAddedDescending() {
        val a = book("A", addedAt = dayN(1))
        val b = book("B", addedAt = dayN(3))
        val c = book("C", percent = 0.3, lastOpenedMs = now - day, addedAt = dayN(2))
        assertEquals(
            listOf(BookItem(c, true), BookItem(b, false), BookItem(a, false)),
            ShelfBuilder.build(listOf(a, b, c), emptyList(), ShelfTab.BOOKS, now),
        )
    }

    @Test fun inProgressOrderedByRecency() {
        val old = book("Old", percent = 0.3, lastOpenedMs = now - 5 * day, addedAt = dayN(20))
        val recent = book("Recent", percent = 0.3, lastOpenedMs = now - day, addedAt = dayN(2))
        assertEquals(
            listOf(BookItem(recent, true), BookItem(old, true)),
            ShelfBuilder.build(listOf(old, recent), emptyList(), ShelfTab.BOOKS, now),
        )
    }

    @Test fun seriesGroupPlacedByInProgressVolumeWithThatVolumeAsCover() {
        val v1 = book("Пикник 1", series = "Пикник", seriesIndex = 1.0, addedAt = dayN(1), status = "complete", percent = 1.0)
        val v2 = book("Пикник 2", series = "Пикник", seriesIndex = 2.0, addedAt = dayN(1), percent = 0.4, lastOpenedMs = now - day)
        val v3 = book("Пикник 3", series = "Пикник", seriesIndex = 3.0, addedAt = dayN(1))
        val d = book("D", addedAt = dayN(10))
        assertEquals(
            listOf(
                SeriesGroup("Пикник", listOf(BookItem(v1, false), BookItem(v2, true), BookItem(v3, false))),
                BookItem(d, false),
            ),
            ShelfBuilder.build(listOf(d, v3, v1, v2), emptyList(), ShelfTab.BOOKS, now),
        )
    }

    @Test fun groupWithoutProgressIsPlacedByNewestMember() {
        val v1 = book("S 1", series = "S", seriesIndex = 1.0, addedAt = dayN(1))
        val v2 = book("S 2", series = "S", seriesIndex = 2.0, addedAt = dayN(9))
        val d = book("D", addedAt = dayN(5))
        assertEquals(
            listOf(SeriesGroup("S", listOf(BookItem(v1, false), BookItem(v2, false))), BookItem(d, false)),
            ShelfBuilder.build(listOf(d, v1, v2), emptyList(), ShelfTab.BOOKS, now),
        )
    }

    @Test fun naturalOrder() {
        assertTrue(ShelfBuilder.naturalOrder.compare("Пикник 2", "Пикник 10") < 0)
        assertTrue(ShelfBuilder.naturalOrder.compare("том 2", "том 10") < 0)
        assertTrue(ShelfBuilder.naturalOrder.compare("пикник 3", "Пикник 10") < 0)
        assertTrue(ShelfBuilder.naturalOrder.compare("Ёж", "Яблоко") < 0)
        val p10 = book("x10", series = "Пикник", file = File("/books/Пикник/Пикник 10.epub"))
        val p2 = book("x2", series = "Пикник", file = File("/books/Пикник/Пикник 2.epub"))
        assertEquals(
            listOf(SeriesGroup("Пикник", listOf(BookItem(p2, false), BookItem(p10, false)))),
            ShelfBuilder.build(listOf(p10, p2), emptyList(), ShelfTab.BOOKS, now),
        )
    }

    @Test fun allTabInterleavesBooksAndMangaBySameRules() {
        val c = book("C", percent = 0.3, lastOpenedMs = now - day, addedAt = dayN(2))
        val a = book("A", addedAt = dayN(1))
        val m1 = manga(1, lastReadAt = now - 2 * day, total = 10, read = 4, dateAdded = dayN(1))
        val m2 = manga(2, lastReadAt = 0, total = 10, read = 0, dateAdded = dayN(2))
        val books = listOf(a, c)
        val mangas = listOf(m2, m1)
        assertEquals(
            listOf(BookItem(c, true), MangaItem(m1, true, false), MangaItem(m2, false, false), BookItem(a, false)),
            ShelfBuilder.build(books, mangas, ShelfTab.ALL, now),
        )
        assertEquals(listOf(BookItem(c, true), BookItem(a, false)), ShelfBuilder.build(books, mangas, ShelfTab.BOOKS, now))
        assertEquals(listOf(MangaItem(m1, true, false), MangaItem(m2, false, false)), ShelfBuilder.build(books, mangas, ShelfTab.MANGA, now))
    }

    // --- now reading ---

    @Test fun nowReadingBookIgnoresMissingFile() {
        val existing = tmp.newFile("existing.epub")
        val gone = File(tmp.root, "gone.epub")
        val older = book("Existing", lastOpenedMs = now - 3 * day, file = existing)
        val ghost = book("Ghost", lastOpenedMs = now - day, file = gone)
        val never = book("Never", file = tmp.newFile("never.epub"))
        assertEquals(older, ShelfBuilder.nowReadingBook(listOf(ghost, older, never)))
        assertNull(ShelfBuilder.nowReadingBook(listOf(ghost, never)))
    }

    @Test fun nowReadingMangaIsLatestWithHistory() {
        val m1 = manga(1, lastReadAt = now - 3 * day, total = 5, read = 1)
        val m2 = manga(2, lastReadAt = now - day, total = 5, read = 5)
        val m3 = manga(3, lastReadAt = 0, total = 5, read = 0)
        assertEquals(m2, ShelfBuilder.nowReadingManga(listOf(m1, m2, m3)))
        assertNull(ShelfBuilder.nowReadingManga(listOf(m3)))
    }

    // --- Book factory ---

    @Test fun bookFromFallsBackToFileNameAndFolderSeries() {
        val bf = BookFile(File("/books/Пикник/Пикник 2.fb2.zip"), folderSeries = "Пикник", addedAt = 42L)
        val b = Book.from(bf, null, null)
        assertEquals("Пикник 2", b.title)
        assertEquals("Пикник", b.series)
        assertNull(b.lastOpened)
        assertEquals(42L, b.addedAt)

        val st = BookState(0.5, "reading", "Пикник на обочине", "Стругацкие", "Миры", 3.0, "ru")
        val b2 = Book.from(bf, st, 1_000L)
        assertEquals(Book(bf.file, "Пикник на обочине", "Стругацкие", "Миры", 3.0, 0.5, "reading", 1_000L, 42L), b2)

        val blank = BookState(null, null, " ", null, "", null, null)
        val b3 = Book.from(BookFile(File("/books/x.epub"), null, 1L), blank, null)
        assertEquals("x", b3.title)
        assertNull(b3.series)
    }

    @Test fun bookFromUsesTheTitleInsideTheFileUntilKoreaderHasOne() {
        val bf = BookFile(File("/books/187310-troetsarstvie.epub"), null, 1L)
        val meta = app.nekoshelf.launcher.data.books.BookMeta("Троецарствие", "Ло Гуаньчжун")
        val b = Book.from(bf, null, null, meta)
        assertEquals("Троецарствие", b.title)
        assertEquals("Ло Гуаньчжун", b.authors)

        val st = BookState(0.1, "reading", "Название из KOReader", "Автор из KOReader", null, null, "ru")
        assertEquals("Название из KOReader", Book.from(bf, st, null, meta).title)
        assertEquals("x", Book.from(BookFile(File("/books/x.epub"), null, 1L), null, null, app.nekoshelf.launcher.data.books.BookMeta(" ", null)).title)
    }
}
