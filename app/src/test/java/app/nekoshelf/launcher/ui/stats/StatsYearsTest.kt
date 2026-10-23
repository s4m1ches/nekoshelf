package app.nekoshelf.launcher.ui.stats

import app.nekoshelf.launcher.domain.DayStat
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsYearsTest {
    private fun d(y: Int) = DayStat(LocalDate.of(y, 3, 1), 1, 60)

    @Test fun noDataIsJustCurrentYear() = assertEquals(listOf(2026), statsYears(emptyList(), emptyList(), 2026))

    @Test fun spansEarliestDataYearToCurrent() =
        assertEquals(listOf(2024, 2025, 2026), statsYears(listOf(d(2025)), listOf(d(2024)), 2026))

    @Test fun futureDataDoesNotExtendPastCurrent() =
        assertEquals(listOf(2026), statsYears(listOf(d(2030)), emptyList(), 2026))
}
