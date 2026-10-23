package app.nekoshelf.launcher.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.nekoshelf.launcher.R

val Literata = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.literata_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

/** Indication that draws nothing: no ripples on e-ink. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}

    override fun equals(other: Any?) = other === this
    override fun hashCode() = -1
}

private val EinkColors: ColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
    outlineVariant = Color.Black,
    error = Color.Black,
    onError = Color.White,
)

private fun einkTypography(): Typography {
    val base = Typography()
    fun TextStyle.lit() = copy(fontFamily = Literata)
    return Typography(
        displayLarge = base.displayLarge.lit(),
        displayMedium = base.displayMedium.lit(),
        displaySmall = base.displaySmall.lit(),
        headlineLarge = base.headlineLarge.lit(),
        headlineMedium = base.headlineMedium.lit(),
        headlineSmall = base.headlineSmall.lit(),
        titleLarge = base.titleLarge.lit(),
        titleMedium = base.titleMedium.lit(),
        titleSmall = base.titleSmall.lit(),
        bodyLarge = base.bodyLarge.lit(),
        bodyMedium = base.bodyMedium.lit(),
        bodySmall = base.bodySmall.lit(),
        labelLarge = base.labelLarge.lit(),
        labelMedium = base.labelMedium.lit(),
        labelSmall = base.labelSmall.lit(),
    )
}

@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColors, typography = einkTypography()) {
        CompositionLocalProvider(
            LocalIndication provides (NoIndication as Indication),
            LocalOverscrollFactory provides null,
            content = content,
        )
    }
}
