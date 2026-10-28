package app.nekoshelf.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class YearOverviewTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun s(m: Int, day: Int, seconds: Int) = DayStat(d(m, day), 0, seconds)

    @Test fun emptyYearIsAllZeros() {
        val o = StatsCalc.overview(emptyList(), emptyList(), 2026, d(10, 28))
        assertEquals(0L, o.seconds)
        assertEquals(0, o.readingDays)
        assertEquals(0, o.bestStreak)
        assertEquals(0, o.currentStreak)
        assertEquals(List(12) { Split(0L, 0L) }, o.months)
        assertEquals(List(7) { Split(0L, 0L) }, o.weekdays)
    }

    @Test fun totalsAndReadingDaysJoinBooksAndMangaAndSkipOtherYears() {
        val books = listOf(s(10, 1, 600), s(10, 2, 0), DayStat(LocalDate.of(2025, 12, 31), 3, 900))
        val manga = listOf(s(10, 1, 300), s(10, 5, 60))
        val o = StatsCalc.overview(books, manga, 2026, d(10, 28))
        assertEquals(960L, o.seconds)
        assertEquals(2, o.readingDays) // 1 Oct counted once; 2 Oct has zero seconds
    }

    @Test fun monthsSplitBooksAndManga() {
        val o = StatsCalc.overview(listOf(s(1, 3, 100), s(1, 9, 50), s(12, 31, 7)), listOf(s(1, 3, 20)), 2026, d(12, 31))
        assertEquals(Split(150L, 20L), o.months[0])
        assertEquals(Split(7L, 0L), o.months[11])
        assertEquals(Split(0L, 0L), o.months[5])
    }

    @Test fun bestStreakIsTheLongestRunInsideTheYear() {
        val books = listOf(s(3, 1, 60), s(3, 2, 60), s(3, 3, 60), s(3, 5, 60), s(3, 6, 60))
        val manga = listOf(s(3, 4, 60)) // manga joins the two runs into one of six days
        assertEquals(6, StatsCalc.overview(books, manga, 2026, d(10, 28)).bestStreak)
        assertEquals(3, StatsCalc.overview(books, emptyList(), 2026, d(10, 28)).bestStreak)
    }

    @Test fun currentStreakCountsBackFromTodayOrYesterday() {
        val days = listOf(s(10, 25, 60), s(10, 26, 60), s(10, 27, 60))
        assertEquals(3, StatsCalc.overview(days, emptyList(), 2026, d(10, 28)).currentStreak) // nothing yet today
        assertEquals(4, StatsCalc.overview(days + s(10, 28, 5), emptyList(), 2026, d(10, 28)).currentStreak)
        assertEquals(0, StatsCalc.overview(days, emptyList(), 2026, d(10, 29)).currentStreak) // a day missed
    }

    @Test fun currentStreakCrossesNewYearButOnlyTheCurrentYearShowsIt() {
        val days = listOf(DayStat(LocalDate.of(2025, 12, 30), 0, 60), DayStat(LocalDate.of(2025, 12, 31), 0, 60), DayStat(LocalDate.of(2026, 1, 1), 0, 60))
        assertEquals(3, StatsCalc.overview(days, emptyList(), 2026, LocalDate.of(2026, 1, 1)).currentStreak)
        assertEquals(1, StatsCalc.overview(days, emptyList(), 2026, LocalDate.of(2026, 1, 1)).bestStreak)
        assertEquals(0, StatsCalc.overview(days, emptyList(), 2025, LocalDate.of(2026, 1, 1)).currentStreak)
    }

    @Test fun weekdaysAverageSecondsFromTheFirstReadingDayToToday() {
        // Mon 19 Oct .. Wed 28 Oct: two Mondays, two Tuesdays, two Wednesdays, one of every other day.
        val books = listOf(s(10, 19, 3600), s(10, 26, 1200), s(10, 24, 600))
        val o = StatsCalc.overview(books, listOf(s(10, 20, 61)), 2026, d(10, 28))
        // Mon (3600+1200)/2 s of books; Tue 61/2 s of manga; Sat 600 s of books
        assertEquals(
            listOf(Split(2400, 0), Split(0, 30), Split(0, 0), Split(0, 0), Split(0, 0), Split(600, 0), Split(0, 0)),
            o.weekdays,
        )
    }

    @Test fun pastYearWeekdaysRunToTheEndOfThatYear() {
        // 2025: first reading Mon 22 Dec; up to Wed 31 Dec there are two Mondays.
        val books = listOf(DayStat(LocalDate.of(2025, 12, 22), 0, 7200))
        val o = StatsCalc.overview(books, emptyList(), 2025, d(10, 28))
        assertEquals(Split(3600, 0), o.weekdays[0])
    }
}
