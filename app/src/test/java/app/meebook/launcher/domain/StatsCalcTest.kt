package app.meebook.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsCalcTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test fun summaryExactTotals() {
        val days = listOf(DayStat(d(10, 22), 10, 600), DayStat(d(10, 23), 7, 300), DayStat(d(9, 1), 3, 100))
        assertEquals(StatsSummary(7, 20L, 300, 1000L), StatsCalc.summary(days, d(10, 23)))
    }

    @Test fun summaryNoToday() {
        assertEquals(StatsSummary(0, 0L, 0, 0L), StatsCalc.summary(emptyList(), d(10, 23)))
    }

    @Test fun weekMondayToSundayFutureZero() {
        val books = listOf(DayStat(d(10, 19), 5, 600), DayStat(d(10, 23), 5, 125), DayStat(d(10, 26), 1, 999))
        val manga = listOf(DayStat(d(10, 19), 5, 300), DayStat(d(10, 24), 1, 3600))
        val w = StatsCalc.week(books, manga, d(10, 23))
        assertEquals((19..25).map { d(10, it) }, w.map { it.first })
        // minutes: Mon (600+300)/60=15, Fri 125/60=2, Sat future -> 0
        assertEquals(listOf(15, 0, 0, 0, 2, 0, 0), w.map { it.second })
    }

    @Test fun heatmapQuartiles() {
        val days = (1..8).map { DayStat(d(3, it), 1, it * 100) } +
            DayStat(d(3, 20), 1, 0) + DayStat(LocalDate.of(2025, 3, 1), 1, 500)
        val h = StatsCalc.heatmap(days, 2026)
        assertEquals(8, h.size)
        assertEquals(1, h[d(3, 1)]); assertEquals(1, h[d(3, 2)])
        assertEquals(2, h[d(3, 3)]); assertEquals(2, h[d(3, 4)])
        assertEquals(3, h[d(3, 5)]); assertEquals(3, h[d(3, 6)])
        assertEquals(4, h[d(3, 7)]); assertEquals(4, h[d(3, 8)])
    }
}
