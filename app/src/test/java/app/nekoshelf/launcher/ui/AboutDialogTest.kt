package app.nekoshelf.launcher.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutDialogTest {
    @Test
    fun tableBecomesRowsWithoutHeaderSeparatorOrDashCells() {
        val md = """
            # Title

            | File | Author | Source | Licence |
            |---|---|---|---|
            | a.svg, b.svg | drawn here | — | own |
            | c.png | Someone | https://example.org/c | CC0 |

            First line
            second line.
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading("Title"),
                MdBlock.Row(listOf("a.svg, b.svg", "drawn here", "own")),
                MdBlock.Row(listOf("c.png", "Someone", "https://example.org/c", "CC0")),
                MdBlock.Paragraph("First line second line."),
            ),
            parseMarkdown(md),
        )
    }

    @Test
    fun theShippedCreditsParseIntoOneRowPerSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open("catgirls/CREDITS.md").bufferedReader().use { it.readText() }
        val blocks = parseMarkdown(text)
        val rows = blocks.filterIsInstance<MdBlock.Row>()
        assertEquals(1, rows.size)
        assertTrue(rows.none { r -> r.cells.any { it == "File" || it.startsWith("---") } })
        assertTrue(blocks.first() is MdBlock.Heading)
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }
}
