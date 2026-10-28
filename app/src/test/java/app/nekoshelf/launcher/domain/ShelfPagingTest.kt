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

    // --- gaps around series ---

    @Test
    fun aGapStandsWhereASeriesMeetsAnotherUnitOnly() {
        val g = SeriesGroup("S", List(2) { book(it) })
        assertEquals(true, gapBetween(g, book(9)))
        assertEquals(true, gapBetween(book(9), g))
        assertEquals(true, gapBetween(g, SeriesGroup("T", List(2) { book(10 + it) })))
        assertEquals(false, gapBetween(book(1), book(2)))
    }

    @Test
    fun gapsTakeRowRoomSoAUnitThatNoLongerFitsMovesOn() {
        // Units are thirds of a slot here: a spine is 3, a gap 1. Group (6) + gap (1) + spine (3) = 10.
        val items = listOf<ShelfItem>(SeriesGroup("S", List(2) { book(it) }), book(9))
        assertEquals(listOf(2), shelfPages(items, slotsPerRow = 10, rows = 3, unitsPerSlot = 3, gap = 1)[0].map { it.size })
        assertEquals(listOf(1, 1), shelfPages(items, slotsPerRow = 9, rows = 3, unitsPerSlot = 3, gap = 1)[0].map { it.size })
        // Without a series neighbour no gap is spent: three spines fill 9 units exactly.
        assertEquals(listOf(3), shelfPages(List(3) { book(it) }, slotsPerRow = 9, rows = 3, unitsPerSlot = 3, gap = 1)[0].map { it.size })
    }

    @Test
    fun aSplitSeriesLeavesRoomForItsGapBeforeTheFirstFragment() {
        // Spine (3) + gap (1) leave 5 of 9 units: one volume (3) fits before the row breaks.
        val items = listOf<ShelfItem>(book(0), SeriesGroup("S", List(4) { book(10 + it) }))
        val rows = shelfPages(items, slotsPerRow = 9, rows = 5, unitsPerSlot = 3, gap = 1)[0]
        assertEquals(listOf(1, 3), rows.map { r -> r.sumOf { (it as? SeriesGroup)?.items?.size ?: 0 } })
    }
}
