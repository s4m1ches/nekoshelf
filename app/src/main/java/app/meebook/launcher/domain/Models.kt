package app.meebook.launcher.domain

import java.time.LocalDate

/** Reading activity for one device-local calendar day. */
data class DayStat(val day: LocalDate, val pages: Int, val seconds: Int)

data class StatsSummary(
    val pagesToday: Int,
    val pagesTotal: Long,
    val secondsToday: Int,
    val secondsTotal: Long,
)
