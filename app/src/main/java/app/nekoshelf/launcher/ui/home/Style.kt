package app.nekoshelf.launcher.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nekoshelf.launcher.R
import app.nekoshelf.launcher.ui.theme.Literata

/** Shared look of the home screen: outlined rounded cards and bold serif, as on the stock home. */
internal val CardShape = RoundedCornerShape(12.dp)
internal val CardBorder = 2.dp
internal val ScreenPadding = 12.dp
internal val Grey = Color(0xFF555555)

internal fun Modifier.outlinedCard(shape: RoundedCornerShape = CardShape): Modifier =
    this.clip(shape).background(Color.White).border(CardBorder, Color.Black, shape)

internal fun serif(sizeSp: Float, bold: Boolean = false, color: Color = Color.Black): TextStyle = TextStyle(
    fontFamily = Literata,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.18f).sp,
    color = color,
)

/**
 * The empty-state text where «Манга» data would be: not installed, or installed but the BRIDGE
 * permission is not granted (ruling R14).
 */
@StringRes
internal fun mangaUnavailableText(installed: Boolean): Int =
    if (installed) R.string.manga_no_access else R.string.empty_manga

/** "3 140": digit groups separated by a narrow no-break space. */
internal fun groupDigits(n: Long): String {
    val s = n.toString()
    val neg = s.startsWith("-")
    val digits = if (neg) s.drop(1) else s
    val out = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(' ')
        out.append(c)
    }
    return (if (neg) "-" else "") + out
}

/**
 * Whether a stats line shows its page count. Time logged without pages (manga history imported
 * from Komikku records only time) shows the time alone instead of a misleading "0 стр.".
 */
internal fun showsPages(pages: Long, seconds: Long): Boolean = pages > 0 || seconds == 0L

/** "42 мин" under an hour, otherwise whole hours "52 ч". */
@Composable
internal fun durationText(seconds: Long): String =
    if (seconds < 3600) stringResource(R.string.minutes, (seconds / 60).toInt())
    else stringResource(R.string.hours, (seconds / 3600).toInt())

/** Thin outlined progress bar, filled black up to [fraction]. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(Color.White).border(1.dp, Color.Black, shape)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.Black))
    }
}
