package app.nekoshelf.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nekoshelf.launcher.R
import app.nekoshelf.launcher.apps.AppEntry

private val ROW = 56.dp

/**
 * A full-screen, paged (never scrolled) list of apps with icons. Used to pick a dock app, and
 * as the swipe-up "all apps" placeholder until Task 10's grid. Back, «Закрыть» or a downward
 * swipe dismiss it; sideways swipes and ‹ › page it. Drawn in-place (no Dialog: no window animation).
 */
@Composable
fun AppPicker(title: String, apps: List<AppEntry>, onPick: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    var page by remember(apps) { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(ScreenPadding),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = serif(20f, bold = true), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.outlinedCard().clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.close), style = serif(15f, bold = true)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val perPage = ((maxHeight - 40.dp) / ROW).toInt().coerceAtLeast(1)
            val pages = apps.chunked(perPage)
            val pageCount = pages.size.coerceAtLeast(1)
            val current = page.coerceIn(0, pageCount - 1)
            Column(
                Modifier.fillMaxSize().swipes(
                    onDown = onDismiss,
                    onLeft = { if (page < pageCount - 1) page++ },
                    onRight = { if (page > 0) page-- },
                ),
            ) {
                for (app in pages.getOrNull(current).orEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().height(ROW).noTapAfterSlop().clickable { onPick(app) }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app, 40.dp)
                        Text(
                            app.label, style = serif(17f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(64.dp, 40.dp).clickable(enabled = current > 0) { page = current - 1 }, contentAlignment = Alignment.Center) {
                        Text("‹", style = serif(24f, bold = true, color = if (current > 0) Color.Black else Color(0xFFAAAAAA)))
                    }
                    Text(stringResource(R.string.pager, current + 1, pageCount), style = serif(14f, bold = true))
                    Box(Modifier.size(64.dp, 40.dp).clickable(enabled = current < pageCount - 1) { page = current + 1 }, contentAlignment = Alignment.Center) {
                        Text("›", style = serif(24f, bold = true, color = if (current < pageCount - 1) Color.Black else Color(0xFFAAAAAA)))
                    }
                }
            }
        }
    }
}
