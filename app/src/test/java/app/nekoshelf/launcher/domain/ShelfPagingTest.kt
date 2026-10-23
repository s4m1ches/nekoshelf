package app.nekoshelf.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ShelfPagingTest {
    private fun book(n: Int, cover: Boolean = false) = BookItem(
        Book(File("/b/$n.epub"), "B$n", null, null, null, null, null, null, n.toLong()),
        asCover = cover,
    )

    private fun slots(items: List<ShelfItem>): Int = items.sumOf { slotWidth(it) }

    @Test
    fun thirtySpinesTwentyPerRowTwoRows() {
        val pages = shelfPages(List(30) { book(it) }, slotsPerRow = 20, rows = 2)
        assertEquals(1, pages.size)
        assertEquals(listOf(20, 10), pages[0].map { it.size })
    }

    @Test
    fun wideGroupIsSplitAcrossRowsNotDropped() {
        val group = SeriesGroup("S", List(25) { book(it) })
        val pages = shelfPages(listOf(group), slotsPerRow = 20, rows = 2)
        assertEquals(1, pages.size)
        val rows = pages[0]
        assertEquals(2, rows.size)
        assertEquals(listOf(20, 5), rows.map { slots(it) })
        val members = rows.flatten().flatMap { (it as SeriesGroup).items }
        assertEquals(group.items, members)
        rows.flatten().forEach { assertEquals("S", (it as SeriesGroup).name) }
    }

    @Test
    fun groupThatFitsARowMovesToNextRowInsteadOfSplitting() {
        val items = listOf<ShelfItem>(
            *Array(15) { book(it) },
            SeriesGroup("S", List(10) { book(100 + it) }),
        )
        val rows = shelfPages(items, slotsPerRow = 20, rows = 3)[0]
        assertEquals(listOf(15, 10), rows.map { slots(it) })
        assertEquals(1, rows[1].size)
    }

    @Test
    fun coverTakesThreeSlotsAndPagesChunkByRows() {
        // 7 covers = 21 slots; 9 per row -> 3 covers per row -> 3 rows; 2 rows per page -> 2 pages
        val pages = shelfPages(List(7) { book(it, cover = true) }, slotsPerRow = 9, rows = 2)
        assertEquals(listOf(2, 1), pages.map { it.size })
        assertEquals(listOf(3, 3), pages[0].map { it.size })
        assertEquals(3, slotWidth(book(0, cover = true)))
        assertEquals(1, slotWidth(book(0)))
    }

    @Test
    fun splitGroupFillsRemainderOfCurrentRowFirst() {
        val items = listOf<ShelfItem>(*Array(5) { book(it) }, SeriesGroup("S", List(30) { book(100 + it) }))
        val rows = shelfPages(items, slotsPerRow = 20, rows = 5)[0]
        assertEquals(listOf(20, 15), rows.map { slots(it) })
        assertEquals(6, rows[0].size) // 5 books + the first group fragment
    }

    @Test
    fun coverWiderThanRowStillPlaced() {
        val pages = shelfPages(listOf(book(1, cover = true), book(2, cover = true)), slotsPerRow = 2, rows = 1)
        assertEquals(2, pages.size)
    }

    @Test
    fun emptyGivesNoPages() {
        assertEquals(0, shelfPages(emptyList(), 20, 2).size)
    }
}
