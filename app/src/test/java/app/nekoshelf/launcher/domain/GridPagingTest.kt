package app.nekoshelf.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GridPagingTest {
    @Test fun emptyListGivesOneEmptyPage() {
        assertEquals(listOf(emptyList<Int>()), gridPages(emptyList<Int>(), 5, 6))
    }

    @Test fun fillsPagesOfColumnsTimesRowsInOrder() {
        val pages = gridPages((1..13).toList(), 5, 2)
        assertEquals(listOf(10, 3), pages.map { it.size })
        assertEquals((1..10).toList(), pages[0])
        assertEquals(listOf(11, 12, 13), pages[1])
    }

    @Test fun exactMultipleHasNoTrailingPage() {
        assertEquals(2, gridPages((1..20).toList(), 5, 2).size)
    }

    @Test fun degenerateSizesStillPage() {
        assertEquals(3, gridPages(listOf(1, 2, 3), 0, 0).size)
    }
}
