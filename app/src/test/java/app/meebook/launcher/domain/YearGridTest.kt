package app.meebook.launcher.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YearGridTest {
    @Test fun mondayFirstRows() {
        // 2026-01-01 is a Thursday: row 3 of column 0; Monday 2026-01-05 starts column 1.
        assertEquals(0 to 3, YearGrid.cell(LocalDate.of(2026, 1, 1)))
        assertEquals(0 to 6, YearGrid.cell(LocalDate.of(2026, 1, 4)))
        assertEquals(1 to 0, YearGrid.cell(LocalDate.of(2026, 1, 5)))
    }

    @Test fun columnsIs53ForCommonYears() {
        assertEquals(53, YearGrid.columns(2026))
        assertEquals(53, YearGrid.columns(2024))
        assertEquals(53, YearGrid.columns(2021)) // starts Friday
    }

    @Test fun columnsGrowsTo54ForLeapYearStartingSunday() {
        assertEquals(54, YearGrid.columns(2012)) // 2012-01-01 is a Sunday, leap
    }

    @Test fun lastDayFitsInLastColumn() {
        for (y in 2000..2060) {
            val (c, r) = YearGrid.cell(LocalDate.of(y, 12, 31))
            assertEquals("year $y", YearGrid.columns(y) - 1, c)
            assertTrue(r in 0..6)
        }
    }

    @Test fun monthLabelsAreTwelveAscendingAndFarApart() {
        val m = YearGrid.monthColumns(2026)
        assertEquals((1..12).toList(), m.map { it.first })
        assertEquals(0, m.first().second)
        m.zipWithNext().forEach { (a, b) -> assertTrue(b.second - a.second >= 4) }
        assertEquals(YearGrid.cell(LocalDate.of(2026, 5, 1)).first, m[4].second)
    }
}
