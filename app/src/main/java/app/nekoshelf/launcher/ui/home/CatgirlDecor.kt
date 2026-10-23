package app.nekoshelf.launcher.ui.home

import android.content.res.AssetManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import app.nekoshelf.launcher.ui.EinkImages
import coil3.compose.AsyncImage

private const val DIR = "catgirls"

/**
 * A small catgirl figure standing / sitting on a line (the header frame's top edge).
 *
 * The figure is scaled to fit [maxWidth] × [height] and its feet / seat are aligned with
 * the bottom of that area — place the composable so its top is [height] above the edge.
 * SVGs that declare `bookshelf:overhang=N` (legs dangling over the edge) extend N
 * viewBox units below it; the composable is that much taller.
 *
 * @param decor asset file name inside `assets/catgirls/` (svg or png), see [catgirlDecors].
 */
@Composable
fun CatgirlDecor(decor: String, height: Dp, maxWidth: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val overhang = remember(decor) { catgirlOverhang(context.assets, decor) }
    AsyncImage(
        model = "file:///android_asset/$DIR/$decor",
        contentDescription = null,
        imageLoader = EinkImages.loader(context),
        contentScale = ContentScale.Fit,
        alignment = Alignment.BottomCenter,
        modifier = modifier.size(maxWidth, height / (1f - overhang)),
    )
}

/** All decor figures bundled in `assets/catgirls/`, sorted by name. */
fun catgirlDecors(assets: AssetManager): List<String> =
    try {
        assets.list(DIR).orEmpty()
            .filter { it.endsWith(".svg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true) }
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

/** Fraction of the figure's height that hangs below the edge (0 for PNGs / no marker). */
internal fun catgirlOverhang(assets: AssetManager, decor: String): Float {
    if (!decor.endsWith(".svg", ignoreCase = true)) return 0f
    return try {
        // Small files (≈4 KB); read synchronously so the figure is placed in one pass.
        val head = assets.open("$DIR/$decor").bufferedReader().use { r ->
            CharArray(2048).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) }
        }
        parseOverhang(head)
    } catch (_: Exception) {
        0f
    }
}

private val OVERHANG = Regex("""bookshelf:overhang=(\d+(?:\.\d+)?)""")
private val VIEWBOX = Regex("""viewBox="\s*[-\d.]+[\s,]+[-\d.]+[\s,]+[-\d.]+[\s,]+([\d.]+)\s*"""")

/** `overhang / viewBox height` from an SVG's text, clamped to [0, 0.5]. */
internal fun parseOverhang(svg: String): Float {
    val overhang = OVERHANG.find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: return 0f
    val height = VIEWBOX.find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: return 0f
    if (height <= 0f) return 0f
    return (overhang / height).coerceIn(0f, 0.5f)
}
