package app.meebook.launcher.domain

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

    /** Intensity 1..4 (quartiles of the non-zero days' seconds) for days in [year]; zero days omitted. */
    fun heatmap(days: List<DayStat>, year: Int): Map<LocalDate, Int> {
        val secs = HashMap<LocalDate, Long>()
        for (s in days) if (s.day.year == year) secs.merge(s.day, s.seconds.toLong(), Long::plus)
        val nonZero = secs.filterValues { it > 0 }
        if (nonZero.isEmpty()) return emptyMap()
        val sorted = nonZero.values.sorted()
        fun q(p: Double) = sorted[(Math.ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.size - 1)]
        val q1 = q(0.25); val q2 = q(0.5); val q3 = q(0.75)
        return nonZero.mapValues { (_, v) ->
            when {
                v <= q1 -> 1
                v <= q2 -> 2
                v <= q3 -> 3
                else -> 4
            }
        }
    }
}
