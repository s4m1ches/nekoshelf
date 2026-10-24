package app.nekoshelf.launcher.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsPagesTest {
    @Test
    fun timeWithoutPagesHidesThePageCount() {
        assertFalse(showsPages(pages = 0, seconds = 9 * 3600))
    }

    @Test
    fun pagesAreShownWhenThereAreAny() {
        assertTrue(showsPages(pages = 495, seconds = 6 * 3600))
        assertTrue(showsPages(pages = 3, seconds = 0))
    }

    @Test
    fun anEmptyDayKeepsZeroPages() {
        assertTrue(showsPages(pages = 0, seconds = 0))
    }
}
