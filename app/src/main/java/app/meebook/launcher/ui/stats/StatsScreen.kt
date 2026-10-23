package app.meebook.launcher.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.data.HomeData
import app.meebook.launcher.domain.DayStat
import app.meebook.launcher.domain.StatsCalc
import app.meebook.launcher.domain.YearGrid
import app.meebook.launcher.ui.home.Grey
import app.meebook.launcher.ui.home.ScreenPadding
import app.meebook.launcher.ui.home.ShelfNav
import app.meebook.launcher.ui.home.durationText
import app.meebook.launcher.ui.home.groupDigits
import app.meebook.launcher.ui.home.mangaUnavailableText
import app.meebook.launcher.ui.home.outlinedCard
import app.meebook.launcher.ui.home.serif
import app.meebook.launcher.ui.home.swipes
import java.time.LocalDate

/** The five intensity levels, lightest (no reading) to darkest (the year's longest day). */
internal val HeatGreys = listOf(Color(0xFFFFFFFF), Color(0xFFC8C8C8), Color(0xFF969696), Color(0xFF5A5A5A), Color(0xFF000000))

private val LABEL_W = 24.dp
private val GAP = 1.dp
/** Rows are taller than columns (53 columns leave the grid short on a portrait screen). */
private const val ROW_RATIO = 2f
private val OUTLINE = Color(0xFF969696)

/** Years offered for paging: from the earliest day with data to the current year, ascending. */
internal fun statsYears(books: List<DayStat>, manga: List<DayStat>, currentYear: Int): List<Int> {
    val first = (books.asSequence() + manga.asSequence()).minOfOrNull { it.day.year }?.coerceAtMost(currentYear) ?: currentYear
    return (first..currentYear).toList()
}

/**
 * Year reading heatmaps, GitHub style: «Книги» and «Манга», one column per week and 7 rows
 * (Monday first; 53 columns, 54 in a leap year starting on Sunday), five grey levels, month
 * labels on top and the year's totals underneath. [nav] pages the years (page 0 = oldest).
 * Back, «Закрыть» or a swipe down returns home; swipes sideways, ‹ › and the hardware page keys
 * change the year. No animation.
 */
@Composable
fun StatsScreen(data: HomeData, nav: ShelfNav, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val years = remember(data.bookDays, data.mangaDays, data.today) { statsYears(data.bookDays, data.mangaDays, data.today.year) }
    SideEffect {
        nav.pageCount = years.size
        if (nav.page >= years.size) nav.page = years.size - 1
    }
    val index = nav.page.coerceIn(0, years.size - 1)
    val year = years[index]
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(ScreenPadding)
            .swipes(onDown = onDismiss, onLeft = { nav.next() }, onRight = { nav.prev() }),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.stats_title), style = serif(20f, bold = true),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.outlinedCard().clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.close), style = serif(15f, bold = true))
            }
        }
        YearPager(year, index > 0, index < years.size - 1, { nav.prev() }, { nav.next() })
        HeatSection(
            title = stringResource(R.string.stats_books),
            days = data.bookDays, year = year, today = data.today,
            note = null, unavailable = null,
        )
        HeatSection(
            title = stringResource(R.string.stats_manga),
            days = data.mangaDays, year = year, today = data.today,
            note = if (data.mangaBackfilled && data.mangaDays.any { it.day.year == year }) stringResource(R.string.stats_manga_backfilled) else null,
            unavailable = if (data.mangaLoaded && !data.mangaAvailable) stringResource(mangaUnavailableText(data.mangaInstalled)) else null,
        )
        Spacer(Modifier.weight(1f))
        Legend()
    }
}

@Composable
private fun YearPager(year: Int, hasPrev: Boolean, hasNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        YearArrow("‹", hasPrev, stringResource(R.string.stats_prev_year), onPrev)
        Text(year.toString(), style = serif(20f, bold = true), modifier = Modifier.padding(horizontal = 18.dp))
        YearArrow("›", hasNext, stringResource(R.string.stats_next_year), onNext)
    }
}

@Composable
private fun YearArrow(glyph: String, enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 64.dp, height = 44.dp).clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = serif(26f, bold = true, color = if (enabled) Color.Black else Color(0xFFAAAAAA)), modifier = Modifier.offset(y = (-3).dp))
    }
}

@Composable
private fun HeatSection(
    title: String,
    days: List<DayStat>,
    year: Int,
    today: LocalDate,
    note: String?,
    unavailable: String?,
) {
    val heat = remember(days, year) { StatsCalc.heatmap(days, year) }
    val pages = remember(days, year) { days.filter { it.day.year == year }.sumOf { it.pages.toLong() } }
    val seconds = remember(days, year) { days.filter { it.day.year == year }.sumOf { it.seconds.toLong() } }
    val months = stringArrayResource(R.array.months_short)
    val weekdays = stringArrayResource(R.array.weekdays_short)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).outlinedCard().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(title, style = serif(16f, bold = true), maxLines = 1)
        if (unavailable != null) {
            Text(unavailable, style = serif(13f, color = Grey), modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val cols = YearGrid.columns(year)
            val pitch = (maxWidth - LABEL_W) / cols
            val rowPitch = pitch * ROW_RATIO
            Column {
                Box(Modifier.fillMaxWidth().height(16.dp)) {
                    YearGrid.monthColumns(year).forEach { (m, col) ->
                        Text(
                            months[m - 1], style = serif(10f, color = Grey), maxLines = 1, softWrap = false,
                            modifier = Modifier.offset(x = LABEL_W + pitch * col),
                        )
                    }
                }
                Box {
                    Column {
                        weekdays.forEach { w ->
                            Box(Modifier.height(rowPitch).width(LABEL_W)) {
                                if (w.isNotEmpty()) {
                                    Text(w, style = serif(10f, color = Grey), maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterStart))
                                }
                            }
                        }
                    }
                    Canvas(Modifier.offset(x = LABEL_W).size(pitch * cols, rowPitch * 7)) {
                        val p = pitch.toPx()
                        val g = GAP.toPx()
                        val rp = p * ROW_RATIO
                        val w = p - g
                        val h = rp - g
                        val hair = Stroke(width = 1f)
                        for ((date, level) in heat) {
                            val (c, r) = YearGrid.cell(date)
                            val topLeft = Offset(c * p, r * rp)
                            val size = Size(w, h)
                            drawRect(HeatGreys[level], topLeft, size)
                            drawRect(OUTLINE, topLeft, size, style = hair)
                            if (date == today) {
                                drawRoundRect(
                                    Color.Black, topLeft - Offset(g, g), Size(w + 2 * g, h + 2 * g),
                                    CornerRadius(2f), style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.stats_year_totals, groupDigits(pages), durationText(seconds)),
            style = serif(13f, bold = true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (note != null) Text(note, style = serif(12f, color = Grey), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.stats_less), style = serif(11f, color = Grey))
        HeatGreys.forEach { c ->
            Box(Modifier.padding(start = 4.dp).size(14.dp).background(c).border(1.dp, Color(0xFF969696)))
        }
        Text(stringResource(R.string.stats_more), style = serif(11f, color = Grey), modifier = Modifier.padding(start = 6.dp))
    }
}
