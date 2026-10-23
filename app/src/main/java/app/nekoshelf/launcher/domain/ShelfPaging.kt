package app.nekoshelf.launcher.domain

/** Shelf width of one unit in slots: a cover takes 3, a spine 1, a series group the sum of its volumes. */
fun slotWidth(item: ShelfItem): Int = when (item) {
    is BookItem -> if (item.asCover) COVER_SLOTS else 1
    is MangaItem -> if (item.asCover) COVER_SLOTS else 1
    is SeriesGroup -> item.items.sumOf { slotWidth(it) }
}

const val COVER_SLOTS = 3

/**
 * Lays [items] out on shelf rows of [slotsPerRow] slots and chunks the rows into pages of [rows].
 * Result: pages → rows → items. Units are packed greedily in order. A series group that fits a
 * row is never split: it moves to the next row when the current one lacks room. A group wider
 * than a row is split into [SeriesGroup] fragments (same name) — the first fills what is left of
 * the current row, the rest continue on the next rows. A single cover wider than the row is
 * still placed, alone. Nothing is ever dropped.
 */
fun shelfPages(items: List<ShelfItem>, slotsPerRow: Int, rows: Int): List<List<List<ShelfItem>>> {
    val perRow = slotsPerRow.coerceAtLeast(1)
    val out = ArrayList<List<ShelfItem>>()
    var row = ArrayList<ShelfItem>()
    var used = 0

    fun newRow() {
        if (row.isNotEmpty()) out += row
        row = ArrayList()
        used = 0
    }

    fun place(item: ShelfItem, w: Int) {
        if (used + w > perRow && row.isNotEmpty()) newRow()
        row += item
        used += w
    }

    for (item in items) {
        val w = slotWidth(item)
        if (item is SeriesGroup && w > perRow) {
            var fragment = ArrayList<BookItem>()
            var fragW = 0
            for (vol in item.items) {
                val vw = slotWidth(vol)
                if (used + fragW + vw > perRow && (fragment.isNotEmpty() || row.isNotEmpty())) {
                    if (fragment.isNotEmpty()) row += SeriesGroup(item.name, fragment)
                    newRow()
                    fragment = ArrayList()
                    fragW = 0
                }
                fragment += vol
                fragW += vw
            }
            if (fragment.isNotEmpty()) {
                row += SeriesGroup(item.name, fragment)
                used += fragW
            }
        } else {
            place(item, w)
        }
    }
    newRow()
    return out.chunked(rows.coerceAtLeast(1))
}
