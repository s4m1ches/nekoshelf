package app.nekoshelf.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.nekoshelf.launcher.R
import app.nekoshelf.launcher.domain.Book
import app.nekoshelf.launcher.domain.BookItem
import app.nekoshelf.launcher.domain.COVER_SLOTS
import app.nekoshelf.launcher.domain.Manga
import app.nekoshelf.launcher.domain.MangaItem
import app.nekoshelf.launcher.domain.SeriesGroup
import app.nekoshelf.launcher.domain.ShelfItem
import app.nekoshelf.launcher.domain.ShelfTab
import app.nekoshelf.launcher.domain.shelfPages
import app.nekoshelf.launcher.domain.slotWidth
import java.io.File

/** The shelf's page position, shared with the hardware-key handler. Paging is instant. */
@Stable
class ShelfNav {
    var page by mutableIntStateOf(0)
    var pageCount by mutableIntStateOf(1)
        internal set

    fun next(): Boolean = if (page < pageCount - 1) { page++; true } else false
    fun prev(): Boolean = if (page > 0) { page--; true } else false
}

/** «Книги / Манга / Всё» as one outlined segmented control (built by hand: no animated indicator). */
@Composable
fun ShelfTabs(tab: ShelfTab, onTab: (ShelfTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding).height(32.dp)
            .clip(shape).border(CardBorder, Color.Black, shape),
    ) {
        val tabs = listOf(
            ShelfTab.BOOKS to R.string.tab_books,
            ShelfTab.MANGA to R.string.tab_manga,
            ShelfTab.ALL to R.string.tab_all,
        )
        tabs.forEachIndexed { i, (t, label) ->
            if (i > 0) Box(Modifier.width(CardBorder).fillMaxHeight().background(Color.Black))
            val selected = t == tab
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selected) Color.Black else Color.White)
                    .noTapAfterSlop().clickable(role = Role.Tab) { if (!selected) onTab(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = serif(14.5f, bold = true, color = if (selected) Color.White else Color.Black))
            }
        }
    }
}

private val PLANK = 6.dp
private val LABEL = 17.dp
private val ROW_GAP = 3.dp
private val PAGER = 28.dp
private val MIN_BOOK = 68.dp
private val MAX_BOOK = 150.dp
private const val COVER_RATIO = 1.45f
private val SHELF_INSET = 6.dp

/**
 * Rows of books on planks, as many rows as fit, paged (swipe, ‹ ›, hardware keys via [nav]).
 * In-progress items are covers (3 slots), the rest spines with vertical titles (1 slot); a
 * series group stands together with its label under the plank. Caught-up manga covers carry
 * «всё прочитано»; in-progress manga covers a chapter progress bar.
 */
@Composable
fun Shelf(
    items: List<ShelfItem>,
    bookCovers: Map<File, File>,
    mangaCovers: Map<Long, File>,
    nav: ShelfNav,
    emptyText: String?,
    onBook: (Book) -> Unit,
    onManga: (Manga) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().swipes(onLeft = { nav.next() }, onRight = { nav.prev() })) {
        val extras = PLANK + LABEL + ROW_GAP
        val avail = maxHeight - PAGER
        val rows = ((avail / (MIN_BOOK + extras)).toInt()).coerceAtLeast(1)
        val bookH = min(MAX_BOOK, avail / rows - extras).coerceAtLeast(40.dp)
        val slotW = bookH / COVER_RATIO / COVER_SLOTS
        val innerW = maxWidth - ScreenPadding * 2 - SHELF_INSET * 2
        val slots = (innerW / slotW).toInt().coerceAtLeast(COVER_SLOTS)

        val pages = remember(items, slots, rows) { shelfPages(items, slots, rows) }
        SideEffect {
            nav.pageCount = pages.size.coerceAtLeast(1)
            if (nav.page >= nav.pageCount) nav.page = nav.pageCount - 1
        }
        val page = pages.getOrNull(nav.page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))).orEmpty()

        Column(Modifier.fillMaxSize()) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        emptyText ?: stringResource(R.string.shelf_empty),
                        style = serif(15f, color = Grey), textAlign = TextAlign.Center,
                    )
                }
            } else {
                for (row in page) {
                    ShelfRow(row, slotW, bookH, bookCovers, mangaCovers, onBook, onManga)
                    Spacer(Modifier.height(ROW_GAP))
                }
                Spacer(Modifier.weight(1f))
            }
            Pager(nav, Modifier.height(PAGER))
        }
    }
}

@Composable
private fun ShelfRow(
    row: List<ShelfItem>,
    slotW: Dp,
    bookH: Dp,
    bookCovers: Map<File, File>,
    mangaCovers: Map<Long, File>,
    onBook: (Book) -> Unit,
    onManga: (Manga) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        Row(Modifier.padding(horizontal = SHELF_INSET).height(bookH), verticalAlignment = Alignment.Bottom) {
            for (unit in row) {
                when (unit) {
                    is BookItem -> BookOnShelf(unit, unit.book.title, slotW, bookH, bookCovers, onBook)
                    is MangaItem -> MangaOnShelf(unit, slotW, bookH, mangaCovers, onManga)
                    is SeriesGroup -> unit.items.forEach {
                        val b = it.book
                        BookOnShelf(it, spineTitle(b.title, unit.name, b.seriesIndex), slotW, bookH, bookCovers, onBook)
                    }
                }
            }
        }
        Plank()
        Row(Modifier.padding(horizontal = SHELF_INSET).height(LABEL)) {
            for (unit in row) {
                val w = slotW * slotWidth(unit)
                Box(Modifier.width(w).fillMaxHeight().padding(top = 2.dp, end = 2.dp)) {
                    if (unit is SeriesGroup) SeriesLabel(unit.name)
                }
            }
        }
    }
}

/** The plank: a dark top edge over a mid-grey front, like a wooden shelf in greyscale. */
@Composable
private fun Plank() {
    val shape = RoundedCornerShape(2.dp)
    Column(Modifier.fillMaxWidth().height(PLANK).clip(shape)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF222222)))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF7A7A7A)))
    }
}

@Composable
private fun SeriesLabel(name: String) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(Color.Black).padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(name, style = serif(11f, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BookOnShelf(item: BookItem, spineText: String, slotW: Dp, bookH: Dp, covers: Map<File, File>, onBook: (Book) -> Unit) {
    val b = item.book
    if (item.asCover) {
        CoverOnShelf(slotW, bookH, onClick = { onBook(b) }) {
            CoverImage(covers[b.file], b.title, slotW * COVER_SLOTS - 3.dp, bookH)
            ProgressBar((b.percent ?: 0.0).toFloat(), Modifier.align(Alignment.BottomCenter).padding(4.dp), height = 5.dp)
        }
    } else {
        Spine(spineText, slotW, bookH, onClick = { onBook(b) })
    }
}

@Composable
private fun MangaOnShelf(item: MangaItem, slotW: Dp, bookH: Dp, covers: Map<Long, File>, onManga: (Manga) -> Unit) {
    val m = item.manga
    if (item.asCover) {
        CoverOnShelf(slotW, bookH, onClick = { onManga(m) }) {
            CoverImage(covers[m.id], m.title, slotW * COVER_SLOTS - 3.dp, bookH)
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (item.caughtUp) {
                    Box(Modifier.fillMaxWidth().background(Color.Black).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.caught_up), style = serif(9f, bold = true, color = Color.White), maxLines = 1)
                    }
                } else {
                    val f = if (m.chaptersTotal > 0) m.chaptersRead.toFloat() / m.chaptersTotal else 0f
                    ProgressBar(f, Modifier.padding(4.dp), height = 5.dp)
                }
                if (m.chaptersNew > 0) NewChaptersStrip(stringResource(R.string.new_chapters_cover, m.chaptersNew))
            }
        }
    } else {
        Spine(
            m.title, slotW, bookH, onClick = { onManga(m) },
            badge = if (m.chaptersNew > 0) stringResource(R.string.new_chapters_spine, m.chaptersNew) else null,
        )
    }
}

/** White strip with a black top rule: «new chapters» on a cover or spine. */
@Composable
private fun NewChaptersStrip(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Black))
        Text(
            text, style = serif(9f, bold = true), maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun CoverOnShelf(slotW: Dp, bookH: Dp, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.width(slotW * COVER_SLOTS).height(bookH).padding(end = 3.dp).noTapAfterSlop().clickable(onClick = onClick),
        content = content,
    )
}

private val SPINE_SHADES = listOf(Color(0xFF2A2A2A), Color(0xFF474747), Color(0xFF1C1C1C), Color(0xFF3A3A3A))

/** A spine: a dark band of slightly varying height with the title running top to bottom; [badge] is a «+N» strip at its foot. */
@Composable
private fun Spine(title: String, slotW: Dp, bookH: Dp, onClick: () -> Unit, badge: String? = null) {
    val h = (title.hashCode() and 0x7fffffff)
    val height = bookH * (0.86f + (h % 15) / 100f)
    val shade = SPINE_SHADES[(h / 15) % SPINE_SHADES.size]
    Box(
        Modifier.width(slotW).height(height).padding(end = 1.5.dp)
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .background(shade)
            .noTapAfterSlop()
            .clickable(onClick = onClick),
    ) {
        // A thin light band near the top, like the label strip on a real spine.
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(1.5.dp).background(Color(0xFFBBBBBB)))
        Text(
            title,
            style = serif(10.5f, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp, bottom = if (badge != null) 22.dp else 6.dp).verticalText(),
        )
        if (badge != null) NewChaptersStrip(badge, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * What a spine inside a series group shows: the title without the series name in front (all
 * volumes would otherwise read the same), or «N · title» when the title doesn't carry the series.
 */
internal fun spineTitle(title: String, series: String?, index: Double?): String {
    if (series.isNullOrBlank()) return title
    val s = series.trim()
    if (title.startsWith(s, ignoreCase = true)) {
        val rest = title.substring(s.length).trimStart(' ', '-', '–', '—', ':', '.', ',', '·', '_')
        return rest.ifEmpty { title }
    }
    if (index == null) return title
    val n = if (index % 1.0 == 0.0) index.toLong().toString() else index.toString()
    return "$n · $title"
}

/** Lays the content out rotated 90° clockwise (reads top to bottom), swapping width and height. */
private fun Modifier.verticalText(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = constraints.maxHeight, minHeight = 0, maxHeight = constraints.maxWidth),
    )
    layout(p.height, p.width) {
        p.placeWithLayer(x = (p.height - p.width) / 2, y = (p.width - p.height) / 2) { rotationZ = 90f }
    }
}

@Composable
internal fun Pager(nav: ShelfNav, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        PagerArrow("‹", nav.page > 0, stringResource(R.string.pager_prev)) { nav.prev() }
        Text(
            stringResource(R.string.pager, nav.page + 1, nav.pageCount),
            style = serif(14f, bold = true),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        PagerArrow("›", nav.page < nav.pageCount - 1, stringResource(R.string.pager_next)) { nav.next() }
    }
}

@Composable
private fun PagerArrow(glyph: String, enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 64.dp, height = PAGER).clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Literata's guillemets sit low on the line: lift them to the counter's optical centre.
        Text(
            glyph,
            style = serif(24f, bold = true, color = if (enabled) Color.Black else Color(0xFFAAAAAA)),
            modifier = Modifier.offset(y = (-3).dp),
        )
    }
}
