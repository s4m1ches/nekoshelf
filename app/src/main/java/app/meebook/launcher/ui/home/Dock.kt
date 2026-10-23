package app.meebook.launcher.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.meebook.launcher.apps.AppEntry

private val ICON = 40.dp

/**
 * The 5 pinned apps with their labels. Tap opens; long-press asks for a replacement
 * ([onLongPress] gets the slot index). An empty slot (app uninstalled) shows an outlined box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dock(slots: List<AppEntry?>, onOpen: (AppEntry) -> Unit, onLongPress: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).height(1.dp).background(Color(0xFF999999)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            slots.forEachIndexed { i, app ->
                Column(
                    Modifier.width(100.dp).noTapAfterSlop().combinedClickable(
                        role = Role.Button,
                        onClickLabel = app?.label,
                        onLongClick = { onLongPress(i) },
                        onClick = { if (app != null) onOpen(app) else onLongPress(i) },
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (app != null) {
                        AppIcon(app, ICON)
                    } else {
                        Box(Modifier.size(ICON).border(CardBorder, Color.Black, RoundedCornerShape(10.dp)))
                    }
                    Text(
                        app?.label.orEmpty(),
                        style = serif(12f, bold = true),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** An app's launcher icon rasterised once at [size]. */
@Composable
internal fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap: ImageBitmap? = remember(app.pkg, app.icon, px) {
        try {
            app.icon.toBitmap(px, px).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size).border(CardBorder, Color.Black, RoundedCornerShape(10.dp)))
    }
}
