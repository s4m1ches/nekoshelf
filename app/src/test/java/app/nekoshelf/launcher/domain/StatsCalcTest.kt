package app.nekoshelf.launcher.domain

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

    private fun year(vararg secs: Int) =
        secs.mapIndexed { i, v -> DayStat(d(3, i + 1), 1, v) }

    @Test fun heatmapCoversEveryDayOfYear() {
        val h = StatsCalc.heatmap(year(100, 0) + DayStat(LocalDate.of(2025, 3, 1), 1, 500), 2026)
        assertEquals(365, h.size)
        assertEquals(366, StatsCalc.heatmap(emptyList(), 2024).size)
        assertEquals(0, h[d(3, 2)]) // zero seconds
        assertEquals(0, h[d(7, 7)]) // no data
        assertEquals(0, h[LocalDate.of(2026, 12, 31)])
    }

    @Test fun heatmapMaxIsFourForOneDay() {
        assertEquals(4, StatsCalc.heatmap(year(100), 2026)[d(3, 1)])
    }

    @Test fun heatmapMaxIsFourForThreeDays() {
        val h = StatsCalc.heatmap(year(100, 200, 300), 2026)
        assertEquals(4, h[d(3, 3)])
        assertEquals(1, h[d(3, 1)]); assertEquals(2, h[d(3, 2)])
    }

    @Test fun heatmapTiesAtTopAreFour() {
        val h = StatsCalc.heatmap(year(100, 300, 300), 2026)
        assertEquals(4, h[d(3, 2)]); assertEquals(4, h[d(3, 3)])
        assertEquals(1, h[d(3, 1)])
    }

    @Test fun heatmapMonotonic() {
        val vals = intArrayOf(50, 700, 100, 800, 300, 200, 400, 600, 500)
        val h = StatsCalc.heatmap(year(*vals), 2026)
        val pairs = vals.indices.map { vals[it] to h.getValue(d(3, it + 1)) }.sortedBy { it.first }
        assertEquals(pairs.map { it.second }.sorted(), pairs.map { it.second })
        assertEquals(4, pairs.last().second)
        assertEquals(1, pairs.first().second)
    }
}
