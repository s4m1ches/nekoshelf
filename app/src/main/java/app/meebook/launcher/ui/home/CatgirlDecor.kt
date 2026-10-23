package app.meebook.launcher.ui.home

import android.content.res.AssetManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.meebook.launcher.ui.EinkImages
import coil3.compose.rememberAsyncImagePainter

private const val DIR = "catgirls"

/** Max decor width as a share of the header width (spec: ≤ 18%; ruling R11: ~16–18%). */
internal const val DECOR_MAX_SHARE = 0.17f
/** Horizontal centre of the decor: between the clock/date block and the toggles. */
internal const val DECOR_CENTER = 0.56f
/** How far down the picture a figure may reach, as a share of the frame height. */
internal const val DECOR_FRAME_OVERLAP = 0.6f
/** Fallback width/height when a figure's size can't be read. */
private const val DEFAULT_ASPECT = 0.6f

/** Where the decor goes inside the header (offsets from the header's content box). */
internal data class DecorPlacement(val left: Dp, val top: Dp, val width: Dp, val height: Dp)

/**
 * Ruling R11: the figure is as large as fits in [DECOR_MAX_SHARE] of the header [width] and
 * [stage] + [DECOR_FRAME_OVERLAP] of the [frame] height, keeping its [aspect] (w/h). It sits
 * on the frame's top edge: the edge crosses the figure's middle, so its lower half is over the
 * picture; a figure taller than twice the stage starts at the very top of the header instead.
 */
internal fun decorPlacement(width: Dp, stage: Dp, frame: Dp, aspect: Float): DecorPlacement {
    val a = if (aspect > 0f) aspect else DEFAULT_ASPECT
    val maxW = width * DECOR_MAX_SHARE
    val maxH = stage + frame * DECOR_FRAME_OVERLAP
    val h = min(maxH, maxW / a)
    val w = h * a
    val top = (stage - h / 2).coerceAtLeast(0.dp)
    return DecorPlacement(width * DECOR_CENTER - w / 2, top, w, h)
}

/**
 * A catgirl figure drawn at [placement] with a thin white halo, so its outline stays legible
 * where it overlaps the header picture.
 *
 * @param decor asset file name inside `assets/catgirls/` (svg or png), see [catgirlDecors].
 */
@Composable
internal fun CatgirlDecor(decor: String, placement: DecorPlacement, modifier: Modifier = Modifier) {
    if (decor.isEmpty()) return
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = "file:///android_asset/$DIR/$decor",
        imageLoader = EinkImages.loader(context),
        contentScale = ContentScale.Fit,
    )
    Box(
        modifier
            .offset(placement.left, placement.top)
            .size(placement.width, placement.height),
    ) {
        val halo = ColorFilter.tint(Color.White, BlendMode.SrcIn)
        val d = 1.5.dp
        for ((dx, dy) in HALO) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
                colorFilter = halo,
                modifier = Modifier.fillMaxSize().offset(d * dx, d * dy),
            )
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val HALO = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f, -0.7f to -0.7f, 0.7f to -0.7f, -0.7f to 0.7f, 0.7f to 0.7f)

/** All decor figures bundled in `assets/catgirls/`, sorted by name. */
fun catgirlDecors(assets: AssetManager): List<String> =
    try {
        assets.list(DIR).orEmpty()
            .filter { it.endsWith(".svg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true) }
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

/** Width / height of a decor asset, read from the PNG header or the SVG viewBox (tiny reads). */
internal fun catgirlAspect(assets: AssetManager, decor: String): Float = try {
    assets.open("$DIR/$decor").use { s ->
        val head = ByteArray(2048)
        val n = s.read(head).coerceAtLeast(0)
        if (decor.endsWith(".png", ignoreCase = true)) {
            pngAspect(head.copyOf(n))
        } else {
            svgAspect(String(head, 0, n, Charsets.UTF_8))
        }
    } ?: DEFAULT_ASPECT
} catch (_: Exception) {
    DEFAULT_ASPECT
}

private val VIEWBOX = Regex("""viewBox="\s*[-\d.]+[\s,]+[-\d.]+[\s,]+([\d.]+)[\s,]+([\d.]+)\s*"""")

internal fun svgAspect(svg: String): Float? {
    val m = VIEWBOX.find(svg) ?: return null
    val w = m.groupValues[1].toFloatOrNull() ?: return null
    val h = m.groupValues[2].toFloatOrNull() ?: return null
    return if (w > 0f && h > 0f) w / h else null
}

internal fun pngAspect(head: ByteArray): Float? {
    if (head.size < 24) return null
    if (head[1] != 0x50.toByte() || head[2] != 0x4E.toByte() || head[3] != 0x47.toByte()) return null
    fun int(o: Int) = ((head[o].toInt() and 0xFF) shl 24) or ((head[o + 1].toInt() and 0xFF) shl 16) or
        ((head[o + 2].toInt() and 0xFF) shl 8) or (head[o + 3].toInt() and 0xFF)
    val w = int(16)
    val h = int(20)
    return if (w > 0 && h > 0) w.toFloat() / h else null
}
