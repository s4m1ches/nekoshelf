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

    /**
     * [YearOverview] of [year]. A reading day has more than zero seconds of books or manga. The
     * current streak counts back from [today] (from yesterday while today has nothing yet) and may
     * reach into the previous year; the best streak counts only days inside [year]. Weekday
     * averages divide by how many of that weekday fall between the year's first reading day and
     * [today] (or 31 December for a past year), books and manga apart.
     */
    fun overview(books: List<DayStat>, manga: List<DayStat>, year: Int, today: LocalDate): YearOverview {
        val all = HashMap<LocalDate, Long>()
        for (s in books + manga) all.merge(s.day, s.seconds.toLong(), Long::plus)
        val bookSecs = HashMap<LocalDate, Long>()
        for (s in books) bookSecs.merge(s.day, s.seconds.toLong(), Long::plus)
        val read = all.filterValues { it > 0 }.keys
        val inYear = read.filter { it.year == year }.sorted()

        val months = MutableList(12) { Split(0L, 0L) }
        for (s in books) if (s.day.year == year) months[s.day.monthValue - 1].let { months[s.day.monthValue - 1] = it.copy(bookSeconds = it.bookSeconds + s.seconds) }
        for (s in manga) if (s.day.year == year) months[s.day.monthValue - 1].let { months[s.day.monthValue - 1] = it.copy(mangaSeconds = it.mangaSeconds + s.seconds) }

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in inYear) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }

        var current = 0
        if (year == today.year) {
            var d = if (today in read) today else today.minusDays(1)
            while (d in read) {
                current++
                d = d.minusDays(1)
            }
        }

        val weekdays = MutableList(7) { Split(0L, 0L) }
        val first = inYear.firstOrNull()
        val last = minOf(today, LocalDate.of(year, 12, 31))
        if (first != null && !first.isAfter(last)) {
            val bookSum = LongArray(7)
            val allSum = LongArray(7)
            val count = IntArray(7)
            var d: LocalDate = first
            while (!d.isAfter(last)) {
                val w = d.dayOfWeek.value - 1
                bookSum[w] += bookSecs[d] ?: 0L
                allSum[w] += all[d] ?: 0L
                count[w]++
                d = d.plusDays(1)
            }
            for (w in 0..6) if (count[w] > 0) {
                weekdays[w] = Split(bookSum[w] / count[w], (allSum[w] - bookSum[w]) / count[w])
            }
        }

        return YearOverview(
            seconds = inYear.sumOf { all.getValue(it) },
            readingDays = inYear.size,
            bestStreak = best,
            currentStreak = current,
            months = months,
            weekdays = weekdays,
        )
    }
}
