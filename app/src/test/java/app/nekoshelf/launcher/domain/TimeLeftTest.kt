package app.nekoshelf.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeLeftTest {
    @Test fun usesThisBooksSpeed() = assertEquals(5420L * 13440 / 240, secondsLeft(6762, 0.1985, ReadSpeed(13440, 240), emptyList()))

    @Test fun tooLittleBookDataFallsBackToAllBooks() =
        assertEquals(100L * 30, secondsLeft(200, 0.5, ReadSpeed(48, 2), listOf(ReadSpeed(600, 20), ReadSpeed(2400, 80))))

    @Test fun noDataMeansNull() = assertNull(secondsLeft(200, 0.5, null, emptyList()))

    @Test fun noPagesOrPercentMeansNull() {
        assertNull(secondsLeft(null, 0.5, ReadSpeed(600, 20), emptyList()))
        assertNull(secondsLeft(200, null, ReadSpeed(600, 20), emptyList()))
    }

    @Test fun formatting() {
        assertEquals("~5 мин", formatLeft(60)); assertEquals("~40 мин", formatLeft(39 * 60))
        assertEquals("~1 ч", formatLeft(58 * 60)); assertEquals("~2 ч 40 мин", formatLeft(2 * 3600 + 38 * 60))
        assertEquals("~12 ч", formatLeft(12 * 3600 + 20 * 60))
    }

    @Test fun nothingLeftMeansNull() {
        assertNull(secondsLeft(200, 1.0, ReadSpeed(600, 20), emptyList()))
        assertNull(secondsLeft(200, 1.2, ReadSpeed(600, 20), emptyList()))
        assertNull(secondsLeft(0, 0.5, ReadSpeed(600, 20), emptyList()))
    }

    @Test fun fallbackUnderTwentyPagesIsNull() =
        assertNull(secondsLeft(200, 0.5, null, listOf(ReadSpeed(100, 10), ReadSpeed(100, 9), ReadSpeed(50, 0))))

    @Test fun bookWithoutSecondsFallsBackToAllBooks() =
        assertEquals(100L * 30, secondsLeft(200, 0.5, ReadSpeed(0, 50), listOf(ReadSpeed(600, 20), ReadSpeed(2400, 80))))

    @Test fun formattingNearTenHours() = assertEquals("~10 ч", formatLeft(9 * 3600 + 58 * 60))
}
