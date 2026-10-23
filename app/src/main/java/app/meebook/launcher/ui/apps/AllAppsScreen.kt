package app.meebook.launcher.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.apps.AppEntry
import app.meebook.launcher.domain.gridPages
import app.meebook.launcher.ui.home.AppIcon
import app.meebook.launcher.ui.home.Grey
import app.meebook.launcher.ui.home.Pager
import app.meebook.launcher.ui.home.ScreenPadding
import app.meebook.launcher.ui.home.ShelfNav
import app.meebook.launcher.ui.home.noTapAfterSlop
import app.meebook.launcher.ui.home.outlinedCard
import app.meebook.launcher.ui.home.serif
import app.meebook.launcher.ui.home.swipes

private const val COLUMNS = 5
private val CELL_HEIGHT = 104.dp
private val CELL_GAP = 6.dp
private val ICON = 56.dp
private val PAGER = 40.dp
private val TITLE_ROW = 48.dp

/**
 * Every installed app as a paged 5-column grid (never scrolled: pages are computed and swapped
 * instantly), icon over a two-line label, in the order given. Tap opens, long-press opens the
 * app's system details page. Back, «Закрыть» or a swipe down returns home; swipes sideways, ‹ ›
 * and the hardware page keys (via [nav]) turn pages.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    nav: ShelfNav,
    onOpen: (AppEntry) -> Unit,
    onDetails: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize().background(Color.White).padding(ScreenPadding)) {
        Row(Modifier.fillMaxWidth().height(TITLE_ROW), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.all_apps), style = serif(20f, bold = true),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.outlinedCard().clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.close), style = serif(15f, bold = true))
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().swipes(onDown = onDismiss, onLeft = { nav.next() }, onRight = { nav.prev() }),
        ) {
            val rows = ((maxHeight - PAGER) / (CELL_HEIGHT + CELL_GAP)).toInt().coerceAtLeast(1)
            val pages = remember(apps, rows) { gridPages(apps, COLUMNS, rows) }
            SideEffect {
                nav.pageCount = pages.size
                if (nav.page >= pages.size) nav.page = pages.size - 1
            }
            val page = pages[nav.page.coerceIn(0, pages.size - 1)]
            Column(Modifier.fillMaxSize()) {
                if (apps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.apps_empty), style = serif(15f, color = Grey), textAlign = TextAlign.Center)
                    }
                } else {
                    for (row in page.chunked(COLUMNS)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = CELL_GAP)) {
                            for (app in row) AppCell(app, onOpen, onDetails)
                            // Keep the last row's cells the same width as full rows.
                            repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
                Pager(nav, Modifier.height(PAGER))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.AppCell(app: AppEntry, onOpen: (AppEntry) -> Unit, onDetails: (AppEntry) -> Unit) {
    Box(Modifier.weight(1f).padding(horizontal = 3.dp)) {
        Column(
            Modifier.fillMaxWidth().height(CELL_HEIGHT).outlinedCard()
                .noTapAfterSlop()
                .combinedClickable(role = Role.Button, onLongClick = { onDetails(app) }, onClick = { onOpen(app) })
                .padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, ICON)
            Text(
                app.label, style = serif(11.5f, bold = true), maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
