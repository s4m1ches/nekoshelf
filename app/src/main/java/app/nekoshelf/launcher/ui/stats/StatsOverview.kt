package app.nekoshelf.launcher.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nekoshelf.launcher.R
import app.nekoshelf.launcher.domain.YearOverview
import app.nekoshelf.launcher.ui.home.CatgirlDecor
import app.nekoshelf.launcher.ui.home.Grey
import app.nekoshelf.launcher.ui.home.durationText
import app.nekoshelf.launcher.ui.home.outlinedCard
import app.nekoshelf.launcher.ui.home.serif
import java.time.LocalDate

/** Manga's share of a stacked bar; books are black. */
private val MangaGrey = Color(0xFF969696)
private val TILE_HEIGHT = 56.dp
private val TILE_DECOR_WIDTH = 48.dp
private val CHART_DECOR_WIDTH = 64.dp
private val CHART_DECOR_MAX = 120.dp

/** Russian noun form for [n]: 1 день, 2 дня, 5 дней, 11 дней, 21 день. */
internal fun <T> russianPlural(n: Int, one: T, few: T, many: T): T {
    val tens = n % 100
    val units = n % 10
    return when {
        tens in 11..14 -> many
        units == 1 -> one
        units in 2..4 -> few
        else -> many
    }
}

@Composable
private fun daysText(n: Int): String =
    stringResource(russianPlural(n, R.string.stats_days_one, R.string.stats_days_few, R.string.stats_days_many), n)

/**
 * The year in four tiles: time read, reading days, average per reading day, and the streak
 * (the current one with the record for this year, the record alone for a past year). [decor]
 * stands at the row's end.
 */
@Composable
internal fun OverviewTiles(o: YearOverview, currentYear: Boolean, decor: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(TILE_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Tile(durationText(o.seconds), stringResource(R.string.stats_tile_total), Modifier.weight(1f))
        Tile(daysText(o.readingDays), stringResource(R.string.stats_tile_days), Modifier.weight(1f))
        val average = if (o.readingDays > 0) o.seconds / o.readingDays else 0L
        Tile(durationText(average), stringResource(R.string.stats_tile_average), Modifier.weight(1f))
        if (currentYear) {
            Tile(daysText(o.currentStreak), stringResource(R.string.stats_tile_streak, o.bestStreak), Modifier.weight(1f))
        } else {
            Tile(daysText(o.bestStreak), stringResource(R.string.stats_tile_best), Modifier.weight(1f))
        }
        if (decor != null) CatgirlDecor(decor, height = TILE_HEIGHT, maxWidth = TILE_DECOR_WIDTH)
    }
}

@Composable
private fun Tile(value: String, caption: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().outlinedCard().padding(horizontal = 7.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, style = serif(16f, bold = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(caption, style = serif(10.5f, color = Grey), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Hours per month (books black, manga grey on top) and average minutes per weekday, side by
 * side with the same bar pitch, and [decor] standing on the baseline at the right. The current
 * month and weekday are labelled in bold for the current year.
 */
@Composable
internal fun ChartsCard(o: YearOverview, year: Int, today: LocalDate, decor: String?, modifier: Modifier = Modifier) {
    val current = year == today.year
    val months = stringArrayResource(R.array.months_short)
    val weekdays = stringArrayResource(R.array.weekdays_short)
    val underHour = stringResource(R.string.stats_under_hour)
    Row(
        modifier.fillMaxWidth().outlinedCard().padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(12f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stats_by_month), style = serif(13f, bold = true), maxLines = 1, modifier = Modifier.weight(1f))
                Swatch(Color.Black, stringResource(R.string.stats_legend_books))
                Swatch(MangaGrey, stringResource(R.string.stats_legend_manga))
            }
            Bars(
                stacks = o.months.map { it.bookSeconds to it.mangaSeconds },
                tops = o.months.map { m ->
                    val s = m.bookSeconds + m.mangaSeconds
                    when {
                        s <= 0L -> ""
                        s < 3600L -> underHour
                        else -> (s / 3600).toString()
                    }
                },
                labels = months.toList(),
                highlight = if (current) today.monthValue - 1 else -1,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(7f).fillMaxHeight()) {
            Text(stringResource(R.string.stats_by_weekday), style = serif(13f, bold = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Bars(
                stacks = o.weekdays.map { it.bookSeconds to it.mangaSeconds },
                tops = o.weekdays.map { w ->
                    val minutes = Math.round((w.bookSeconds + w.mangaSeconds) / 60.0)
                    if (minutes > 0) minutes.toString() else ""
                },
                labels = weekdays.toList(),
                highlight = if (current) today.dayOfWeek.value - 1 else -1,
                modifier = Modifier.weight(1f),
            )
        }
        if (decor != null) {
            BoxWithConstraints(Modifier.fillMaxHeight().width(CHART_DECOR_WIDTH), contentAlignment = Alignment.BottomCenter) {
                CatgirlDecor(decor, height = minOf(maxHeight, CHART_DECOR_MAX), maxWidth = CHART_DECOR_WIDTH)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Box(Modifier.padding(start = 6.dp).size(9.dp).background(color).border(1.dp, Color.Black))
    Text(label, style = serif(10f, color = Grey), maxLines = 1, modifier = Modifier.padding(start = 3.dp))
}

/**
 * One column per value: its number on top, a stacked bar (first part black at the bottom, the
 * second grey above it) scaled to the tallest, a shared baseline and the label underneath. An
 * empty column shows only the baseline.
 */
@Composable
private fun Bars(stacks: List<Pair<Long, Long>>, tops: List<String>, labels: List<String>, highlight: Int, modifier: Modifier) {
    val max = stacks.maxOfOrNull { it.first + it.second }?.coerceAtLeast(1L) ?: 1L
    Row(modifier.fillMaxWidth().padding(top = 2.dp)) {
        stacks.forEachIndexed { i, (black, grey) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val room = maxHeight - 13.dp
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tops.getOrElse(i) { "" }, style = serif(9f, bold = i == highlight), maxLines = 1, softWrap = false)
                        Bar(grey, max, room, MangaGrey)
                        Bar(black, max, room, Color.Black)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
                Text(
                    labels.getOrElse(i) { "" }, style = serif(9f, bold = i == highlight, color = if (i == highlight) Color.Black else Grey),
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun Bar(value: Long, max: Long, room: Dp, color: Color) {
    if (value <= 0L || room <= 0.dp) return
    val h = (room * (value.toFloat() / max)).coerceAtLeast(2.dp)
    Box(Modifier.fillMaxWidth(0.62f).height(h).background(color, RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)))
}
