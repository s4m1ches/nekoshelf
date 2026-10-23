package app.meebook.launcher.domain

/** Splits [items] into pages of `columns * rows` cells, in order. Always at least one (possibly empty) page. */
fun <T> gridPages(items: List<T>, columns: Int, rows: Int): List<List<T>> {
    val perPage = (columns.coerceAtLeast(1) * rows.coerceAtLeast(1))
    return items.chunked(perPage).ifEmpty { listOf(emptyList()) }
}
