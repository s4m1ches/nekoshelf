package app.meebook.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.meebook.launcher.R
import app.meebook.launcher.device.Toggles
import app.meebook.launcher.domain.DECOR_SLOTS
import app.meebook.launcher.domain.DecorSpot
import app.meebook.launcher.ui.EinkImages
import app.meebook.launcher.ui.theme.Literata
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Header height as a share of the screen height. */
private const val HEADER_SHARE = 0.25f
/**
 * Share of the header height above the picture frame — the catgirls' "stage". Task 8 had 0.27;
 * the owner asked for the figure ~10% larger, still standing in the strip (fix round 1 of
 * Task 9, reversing R11's overlap), so the strip grew to 0.30 (≈ +4.5 dp, taken from the picture).
 * Then another +10% with the feet [DECOR_SINK] lower: 0.315 (≈ +2.7 dp, again from the picture).
 */
private const val STAGE_SHARE = 0.315f
/**
 * Share of a figure's height that sinks below the frame's top line — the owner asked for the
 * figures 5% lower. The figure's top still meets the header's top, so nothing is clipped.
 */
private const val DECOR_SINK = 0.05f
/** Max decor width as a share of the header width (spec: ≤ 18%); slots are 1/[DECOR_SLOTS] wide. */
private const val DECOR_MAX_SHARE = 0.18f

private val FallbackGrey = Color(0xFFEEEEEE)
private val RU = Locale.forLanguageTag("ru")
private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU)
private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

/**
 * The home screen header (~25% of the screen height): the [picture] cropped into an
 * outlined rounded frame (flat grey band if null or undecodable), the clock and the
 * Russian date top-left, three round toggles top-right (Wi‑Fi, frontlight, full
 * refresh; filled = on; long-press opens the system screen) with the battery % under
 * them, and the [decors] catgirls standing in the strip above the frame, on its top edge.
 * [onPictureSettled] fires once the picture has been drawn or has failed to load; a long-press
 * on the clock calls [onAbout].
 *
 * The clock ticks once a minute while the screen is started; toggle states and battery
 * are re-read then too, and Wi‑Fi follows WIFI_STATE_CHANGED.
 */
@Composable
fun Header(
    picture: File?,
    decors: List<DecorSpot>,
    toggles: Toggles,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onPictureSettled: () -> Unit = {},
    onAbout: () -> Unit = {},
    onTick: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var wifiOn by remember { mutableStateOf(toggles.wifiOn()) }
    var lightOn by remember { mutableStateOf(toggles.lightOn()) }
    var battery by remember { mutableIntStateOf(toggles.battery()) }

    LaunchedEffect(toggles, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = LocalDateTime.now()
                wifiOn = toggles.wifiOn()
                lightOn = toggles.lightOn()
                battery = toggles.battery()
                onTick()
                // Wake just after the next minute boundary. Not an animation: one redraw a minute.
                delay(60_000L - System.currentTimeMillis() % 60_000L + 200L)
            }
        }
    }

    DisposableEffect(context, toggles) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Ignore the transient ENABLING/DISABLING states so the icon flips once.
                when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                    WifiManager.WIFI_STATE_ENABLED -> wifiOn = true
                    WifiManager.WIFI_STATE_DISABLED -> wifiOn = false
                }
            }
        }
        // EXPORTED is required: below API 33 ContextCompat implements NOT_EXPORTED with an
        // app-private permission, which the system's WIFI_STATE_CHANGED sender lacks.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    HeaderContent(
        picture = picture,
        decors = decors,
        time = TIME.format(now),
        date = DATE.format(now),
        wifiOn = wifiOn,
        lightOn = lightOn,
        battery = battery,
        onWifi = {
            val target = !wifiOn
            // Refused in airplane mode: let the system panel do it (one more tap for the owner).
            if (toggles.setWifi(target)) wifiOn = target else toggles.openWifiPanel()
        },
        onWifiLong = toggles::openWifiSettings,
        onLight = {
            if (toggles.setLight(!lightOn)) lightOn = toggles.lightOn()
        },
        onLightLong = toggles::openLightPanel,
        onRefresh = onRefresh,
        modifier = modifier,
        onPictureSettled = onPictureSettled,
        onClockLongPress = onAbout,
    )
}

/** Stateless header layout; [Header] feeds it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HeaderContent(
    picture: File?,
    decors: List<DecorSpot>,
    time: String,
    date: String,
    wifiOn: Boolean,
    lightOn: Boolean,
    battery: Int,
    onWifi: () -> Unit,
    onWifiLong: () -> Unit,
    onLight: () -> Unit,
    onLightLong: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onPictureSettled: () -> Unit = {},
    onClockLongPress: () -> Unit = {},
) {
    val headerHeight = LocalConfiguration.current.screenHeightDp.dp * HEADER_SHARE
    val stage = headerHeight * STAGE_SHARE
    val frameShape = RoundedCornerShape(14.dp)
    val border = 2.dp
    // Assume the picture decodes; flip to dark text only if Coil reports an error.
    var pictureShown by remember(picture) { mutableStateOf(picture != null) }

    BoxWithConstraints(modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 12.dp)) {
        val width = maxWidth
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = stage, bottom = 4.dp)
                .clip(frameShape)
                .background(FallbackGrey)
                .border(border, Color.Black, frameShape),
        ) {
            if (picture != null) {
                HeaderPicture(
                    picture = picture,
                    width = width,
                    height = headerHeight - stage - 4.dp,
                    onSuccess = onPictureSettled,
                    onError = { pictureShown = false; onPictureSettled() },
                )
            }
            // Long-press on the clock: «О приложении». A plain tap does nothing.
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp)
                    .noTapAfterSlop()
                    .combinedClickable(
                        onLongClickLabel = stringResource(R.string.about_title),
                        onLongClick = onClockLongPress,
                        onClick = {},
                    ),
            ) {
                OutlinedText(time, 46, FontWeight.Bold, outlined = pictureShown)
                OutlinedText(date, 17, FontWeight.Bold, outlined = pictureShown)
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleButton(WifiIcon, wifiOn, stringResource(R.string.toggle_wifi), onWifi, onWifiLong)
                    ToggleButton(LightIcon, lightOn, stringResource(R.string.toggle_light), onLight, onLightLong)
                    ToggleButton(RefreshIcon, false, stringResource(R.string.toggle_refresh), onRefresh, null)
                }
                if (battery >= 0) {
                    OutlinedText(
                        stringResource(R.string.battery_percent, battery), 15, FontWeight.Bold,
                        outlined = pictureShown, modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    )
                }
            }
        }
        val decorWidth = width * DECOR_MAX_SHARE
        val slotWidth = width / DECOR_SLOTS
        // +border: the feet / seat rest on the frame line, then sink DECOR_SINK of the height.
        val decorHeight = (stage + border) / (1f - DECOR_SINK)
        for (spot in decors) {
            key(spot.slot) {
                CatgirlDecor(
                    decor = spot.decor,
                    height = decorHeight,
                    maxWidth = decorWidth,
                    modifier = Modifier.offset(x = slotWidth * (spot.slot + 0.5f) - decorWidth / 2),
                )
            }
        }
    }
}

@Composable
private fun HeaderPicture(picture: File, width: Dp, height: Dp, onSuccess: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val wPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val hPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    // Explicit target size: a 9000×6000 JPEG is decoded subsampled to about the frame size.
    val request = remember(picture, wPx, hPx) {
        ImageRequest.Builder(context)
            .data(picture)
            .size(wPx, hPx)
            .scale(Scale.FILL)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = EinkImages.loader(context),
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        onSuccess = { onSuccess() },
        onError = { onError() },
        modifier = Modifier.fillMaxSize(),
    )
}

/** White text with a black outline over a picture (legible on any greys); plain black otherwise. */
@Composable
private fun OutlinedText(
    text: String,
    sizeSp: Int,
    weight: FontWeight,
    outlined: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(fontFamily = Literata, fontWeight = weight, fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.15f).sp)
    Box(modifier) {
        if (outlined) {
            Text(
                text,
                style = style.copy(
                    color = Color.Black,
                    drawStyle = Stroke(width = sizeSp * 0.28f, join = StrokeJoin.Round),
                ),
            )
            Text(text, style = style.copy(color = Color.White))
        } else {
            Text(text, style = style.copy(color = Color.Black))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleButton(
    icon: ImageVector,
    on: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .size(44.dp)
            // Thin white ring outside the black one keeps a filled button distinct on dark pictures.
            .border(2.dp, Color.White, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (on) Color.Black else Color.White)
            .border(2.dp, Color.Black, CircleShape)
            .combinedClickable(
                onClickLabel = label,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(if (on) Color.White else Color.Black),
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun strokeIcon(name: String, vararg paths: String, filled: String? = null, width: Float = 2.3f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        if (filled != null) addPath(pathData = addPathNodes(filled), fill = SolidColor(Color.Black))
    }.build()

// Icon paths from the home-layout-v2 mockup.
private val WifiIcon = strokeIcon(
    "wifi",
    "M2 9a15 15 0 0 1 20 0M5.5 12.5a10 10 0 0 1 13 0M9 16a5 5 0 0 1 6 0",
    filled = "M10.6 19.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z",
)
private val LightIcon = strokeIcon(
    "light",
    "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z",
    "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    width = 2.1f,
)
private val RefreshIcon = strokeIcon("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6")
