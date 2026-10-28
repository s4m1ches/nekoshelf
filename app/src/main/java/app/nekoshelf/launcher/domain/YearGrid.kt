package app.nekoshelf.launcher.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Geometry of a GitHub-style year grid: columns are weeks, rows are Monday..Sunday. */
object YearGrid {
    /** (column, row) of [d] within its own year's grid. Column 0 is the week holding 1 January. */
    fun cell(d: LocalDate): Pair<Int, Int> {
        val offset = LocalDate.of(d.year, 1, 1).dayOfWeek.value - 1
        val index = d.dayOfYear - 1 + offset
        return index / 7 to index % 7
    }

    /** Number of week columns for [year]: 53 normally, 54 for a leap year that starts on a Sunday. */
    fun columns(year: Int): Int = cell(LocalDate.of(year, 12, 31)).first + 1

    /** (month 1..12, column of that month's first Monday): a label stands over the month's first whole week. */
    fun monthColumns(year: Int): List<Pair<Int, Int>> =
        (1..12).map { it to cell(LocalDate.of(year, it, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))).first }
}
