package app.meebook.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.domain.StatsSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val ZERO = StatsSummary(0, 0, 0, 0)

/**
 * Two outlined blocks side by side, «Книги» and «Манга», each with today's and the total pages
 * and reading time, and under them the week's reading minutes (books + manga, Monday first,
 * today's bar outlined). [manga] null means «Манга» is not available
 * (not installed, or — with [mangaInstalled] — no bridge permission). Until [loaded] the
 * blocks show only their titles (no premature zeros or empty states). Tapping a block opens the
 * year heatmap screen ([onOpenStats]).
 */
@Composable
fun StatsBlock(
    books: StatsSummary?,
    manga: StatsSummary?,
    mangaInstalled: Boolean,
    mangaLoaded: Boolean,
    week: List<Pair<LocalDate, Int>>,
    today: LocalDate,
    loaded: Boolean,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                stringResource(R.string.stats_books), books ?: ZERO,
                if (!loaded) "" else null,
                Modifier.weight(1f).fillMaxHeight().noTapAfterSlop().clickable(role = Role.Button, onClick = onOpenStats),
            )
            StatCard(
                stringResource(R.string.stats_manga), manga ?: ZERO,
                when {
                    !loaded || !mangaLoaded -> ""
                    manga == null -> stringResource(mangaUnavailableText(mangaInstalled))
                    else -> null
                },
                Modifier.weight(1f).fillMaxHeight().noTapAfterSlop().clickable(role = Role.Button, onClick = onOpenStats),
            )
        }
        WeekBars(week, today, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatCard(title: String, s: StatsSummary, emptyText: String?, modifier: Modifier) {
    Column(modifier.outlinedCard().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(title, style = serif(14f, bold = true), maxLines = 1)
        if (emptyText != null) {
            Text(emptyText, style = serif(12.5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(
                stringResource(R.string.stats_today, groupDigits(s.pagesToday.toLong()), durationText(s.secondsToday.toLong())),
                style = serif(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.stats_total, groupDigits(s.pagesTotal), durationText(s.secondsTotal)),
                style = serif(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val BAR_MAX = 18.dp
private val BAR_WIDTH = 30.dp

/** Seven bars (minutes per day); today's is drawn outlined. A day without reading shows a stub. */
@Composable
private fun WeekBars(week: List<Pair<LocalDate, Int>>, today: LocalDate, modifier: Modifier = Modifier) {
    val letters = stringResource(R.string.week_days)
    val max = week.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        val days = week.ifEmpty {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (0L..6L).map { monday.plusDays(it) to 0 }
        }
        days.forEachIndexed { i, (day, minutes) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(BAR_MAX).width(BAR_WIDTH), contentAlignment = Alignment.BottomCenter) {
                    val h = if (minutes <= 0) 2.dp else (BAR_MAX * (minutes.toFloat() / max)).coerceAtLeast(4.dp)
                    val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                    if (day == today) {
                        Box(
                            Modifier.width(BAR_WIDTH).height(h.coerceAtLeast(6.dp))
                                .background(Color.White, shape).border(2.dp, Color.Black, shape),
                        )
                    } else {
                        Box(Modifier.width(BAR_WIDTH).height(h).background(if (minutes > 0) Color.Black else Grey, shape))
                    }
                }
                Text(
                    letters.getOrElse(i) { ' ' }.toString() + if (minutes > 0) " $minutes" else "",
                    style = serif(10.5f, bold = day == today),
                    maxLines = 1,
                )
            }
        }
    }
}
