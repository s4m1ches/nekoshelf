package app.meebook.launcher.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meebook.launcher.R
import app.meebook.launcher.ui.home.Grey
import app.meebook.launcher.ui.home.ScreenPadding
import app.meebook.launcher.ui.home.outlinedCard
import app.meebook.launcher.ui.home.serif
import app.meebook.launcher.ui.home.swipes

/** One block of a small Markdown file, as the About screen draws it. */
internal sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    /** A table body row: its cells, trimmed, with «—» / empty cells dropped. */
    data class Row(val cells: List<String>) : MdBlock
    /** Consecutive text lines joined with spaces. */
    data class Paragraph(val text: String) : MdBlock
}

/**
 * Just enough Markdown for CREDITS.md: `#` headings, a pipe table (its header and `|---|` rows
 * are dropped, body rows become [MdBlock.Row]) and paragraphs. Inline markup is left as is.
 */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out.add(MdBlock.Paragraph(para.toString()))
        para.clear()
    }
    var tableHeaderSeen = false
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> { flush(); tableHeaderSeen = false }
            line.startsWith("#") -> { flush(); out.add(MdBlock.Heading(line.trimStart('#').trim())) }
            line.startsWith("|") -> {
                flush()
                val cells = line.trim('|').split('|').map { it.trim() }
                when {
                    cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } } -> Unit // separator
                    !tableHeaderSeen -> tableHeaderSeen = true // the header row
                    else -> out.add(MdBlock.Row(cells.filter { it.isNotEmpty() && it != "—" && it != "-" }))
                }
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
        }
    }
    flush()
    return out
}

private fun readAsset(context: Context, path: String): String =
    runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")

/**
 * «О приложении» (long-press on the clock): the app version, the catgirl decor credits from
 * `assets/catgirls/CREDITS.md`, and the Literata font's OFL notice. Drawn in place over the
 * home screen like the other overlays (no Dialog window, no animation); fits one screen, no
 * scrolling. Back, «Закрыть» or a swipe down closes it.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val credits = remember { parseMarkdown(readAsset(context, "catgirls/CREDITS.md")) }
    val fontCopyright = remember {
        readAsset(context, "licenses/literata-OFL.txt").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(ScreenPadding).swipes(onDown = onDismiss),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.about_title), style = serif(20f, bold = true),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.outlinedCard().clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.close), style = serif(15f, bold = true))
            }
        }
        Text(stringResource(R.string.about_app, version), style = serif(15f, bold = true), modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.about_purpose), style = serif(12.5f, color = Grey))

        for (block in credits) {
            when (block) {
                is MdBlock.Heading -> Text(block.text, style = serif(15f, bold = true), modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                is MdBlock.Row -> Column(Modifier.padding(top = 6.dp)) {
                    Text(block.cells.firstOrNull().orEmpty(), style = serif(12.5f, bold = true))
                    val rest = block.cells.drop(1)
                    // Author · licence, then the source URL on its own line (long, grey).
                    val (urls, words) = rest.partition { it.startsWith("http") }
                    if (words.isNotEmpty()) Text(words.joinToString(" · "), style = serif(12.5f))
                    for (u in urls) Text(u, style = serif(11f, color = Grey))
                }
                is MdBlock.Paragraph -> Text(block.text, style = serif(12.5f), modifier = Modifier.padding(top = 8.dp))
            }
        }

        Text(stringResource(R.string.about_font_title), style = serif(15f, bold = true), modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
        Text(stringResource(R.string.about_font), style = serif(12.5f))
        if (fontCopyright.isNotEmpty()) Text(fontCopyright, style = serif(11f, color = Grey))
    }
}
