package app.nekoshelf.launcher.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object StatsCalc {
    fun summary(days: List<DayStat>, today: LocalDate): StatsSummary {
        val t = days.filter { it.day == today }
        return StatsSummary(
            pagesToday = t.sumOf { it.pages },
            pagesTotal = days.sumOf { it.pages.toLong() },
            secondsToday = t.sumOf { it.seconds },
            secondsTotal = days.sumOf { it.seconds.toLong() },
        )
    }

    /** Monday..Sunday of [today]'s week; minutes (books + manga); days after [today] are 0. */
    fun week(books: List<DayStat>, manga: List<DayStat>, today: LocalDate): List<Pair<LocalDate, Int>> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val secs = HashMap<LocalDate, Long>()
        for (s in books + manga) secs.merge(s.day, s.seconds.toLong(), Long::plus)
        return (0L..6L).map { i ->
            val d = monday.plusDays(i)
            d to if (d.isAfter(today)) 0 else ((secs[d] ?: 0L) / 60).toInt()
        }
    }

    /**
     * Intensity 0..4 for EVERY calendar day of [year] (365/366 entries). Days with no reading or
     * zero seconds are 0. Among non-zero days the day(s) with the maximum seconds are always 4;
     * the others get 1..3 by the 25th/50th percentile (ceil-rank) of the non-zero seconds:
     * <= p25 -> 1, <= p50 -> 2, otherwise 3. Monotonic non-decreasing in seconds.
     */
    fun heatmap(days: List<DayStat>, year: Int): Map<LocalDate, Int> {
        val secs = HashMap<LocalDate, Long>()
        for (s in days) if (s.day.year == year) secs.merge(s.day, s.seconds.toLong(), Long::plus)
        val sorted = secs.values.filter { it > 0 }.sorted()
        val max = sorted.lastOrNull() ?: 0L
        fun q(p: Double) = sorted[(Math.ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.size - 1)]
        val q1 = if (sorted.isEmpty()) 0L else q(0.25)
        val q2 = if (sorted.isEmpty()) 0L else q(0.5)
        val out = LinkedHashMap<LocalDate, Int>()
        var d = LocalDate.of(year, 1, 1)
        while (d.year == year) {
            val v = secs[d] ?: 0L
            out[d] = when {
                v <= 0L -> 0
                v >= max -> 4
                v <= q1 -> 1
                v <= q2 -> 2
                else -> 3
            }
            d = d.plusDays(1)
        }
        return out
    }
}
