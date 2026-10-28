package app.nekoshelf.launcher.domain

/** Shelf width of one unit in slots: a cover takes 3, a spine 1, a series group the sum of its volumes. */
fun slotWidth(item: ShelfItem): Int = when (item) {
    is BookItem -> if (item.asCover) COVER_SLOTS else 1
    is MangaItem -> if (item.asCover) COVER_SLOTS else 1
    is SeriesGroup -> item.items.sumOf { slotWidth(it) }
}

const val COVER_SLOTS = 3

/** Whether a gap stands between neighbours [a] and [b] on a row: wherever a series meets another unit. */
fun gapBetween(a: ShelfItem, b: ShelfItem): Boolean = a is SeriesGroup || b is SeriesGroup

/**
 * Lays [items] out on shelf rows and chunks the rows into pages of [rows]. Result: pages → rows
 * → items. Widths are counted in units: a slot is [unitsPerSlot] units, a row [slotsPerRow] units
 * (slots when [unitsPerSlot] is 1), and [gap] units stand between neighbours where [gapBetween]
 * says so. Units are packed greedily in order. A series group that fits a row is never split: it
 * moves to the next row when the current one lacks room. A group wider than a row is split into
 * [SeriesGroup] fragments (same name) — the first fills what is left of the current row, the rest
 * continue on the next rows. A single cover wider than the row is still placed, alone. Nothing is
 * ever dropped.
 */
fun shelfPages(items: List<ShelfItem>, slotsPerRow: Int, rows: Int, unitsPerSlot: Int = 1, gap: Int = 0): List<List<List<ShelfItem>>> {
    val perRow = slotsPerRow.coerceAtLeast(1)
    val out = ArrayList<List<ShelfItem>>()
    var row = ArrayList<ShelfItem>()
    var used = 0

    fun newRow() {
        if (row.isNotEmpty()) out += row
        row = ArrayList()
        used = 0
    }

    fun gapBefore(item: ShelfItem) = if (row.isNotEmpty() && gapBetween(row.last(), item)) gap else 0

    fun place(item: ShelfItem, w: Int) {
        if (used + gapBefore(item) + w > perRow && row.isNotEmpty()) newRow()
        used += gapBefore(item) + w
        row += item
    }

    for (item in items) {
        val w = slotWidth(item) * unitsPerSlot
        if (item is SeriesGroup && w > perRow) {
            var lead = gapBefore(item)
            var fragment = ArrayList<BookItem>()
            var fragW = 0
            for (vol in item.items) {
                val vw = slotWidth(vol) * unitsPerSlot
                if (used + lead + fragW + vw > perRow && (fragment.isNotEmpty() || row.isNotEmpty())) {
                    if (fragment.isNotEmpty()) row += SeriesGroup(item.name, fragment)
                    newRow()
                    fragment = ArrayList()
                    fragW = 0
                    lead = 0
                }
                fragment += vol
                fragW += vw
            }
            if (fragment.isNotEmpty()) {
                row += SeriesGroup(item.name, fragment)
                used += lead + fragW
            }
        } else {
            place(item, w)
        }
    }
    newRow()
    return out.chunked(rows.coerceAtLeast(1))
}
