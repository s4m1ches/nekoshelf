package app.nekoshelf.launcher.domain

import java.text.Collator
import java.util.Locale

/**
 * Pure ordering/grouping of the bookshelf.
 *
 * Top-level units are single books, series groups and manga titles. In-progress units (covers)
 * come first by recency descending (books: lastOpened s * 1000, manga: lastReadAt ms); all other
 * units (spines) follow by addedAt/dateAdded descending. A series group is in progress if any
 * volume is; its recency is the latest in-progress volume's, its added time the newest volume's.
 */
object ShelfBuilder {
    private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

    fun isInProgress(b: Book): Boolean {
        if (b.status == "reading") return true
        if (b.status != null) return false
        val p = b.percent ?: return false
        return p > 0.0 && p < 1.0
    }

    fun isInProgress(m: Manga, nowMs: Long): Boolean {
        if (m.lastReadAt <= 0) return false
        val hasUnread = m.chaptersRead < m.chaptersTotal
        return hasUnread || nowMs - m.lastReadAt <= THIRTY_DAYS_MS
    }

    fun build(books: List<Book>, manga: List<Manga>, tab: ShelfTab, nowMs: Long): List<ShelfItem> {
        val entries = ArrayList<Entry>()
        if (tab != ShelfTab.MANGA) entries += bookUnits(books)
        if (tab != ShelfTab.BOOKS) entries += manga.map { mangaUnit(it, nowMs) }
        return entries.sortedWith(entryOrder).map { it.item }
    }

    /** The book with the latest lastOpened whose file still exists (no ghost «Сейчас читаю»). */
    fun nowReadingBook(books: List<Book>): Book? =
        books.filter { it.lastOpened != null && it.file.exists() }.maxByOrNull { it.lastOpened!! }

    /** The manga title with the latest lastReadAt > 0. */
    fun nowReadingManga(manga: List<Manga>): Manga? =
        manga.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }

    /** Case-insensitive, Russian-aware order that compares digit runs numerically ("2" < "10"). */
    val naturalOrder: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    // --- internals ---

    private class Entry(
        val item: ShelfItem,
        val inProgress: Boolean,
        val recency: Long,
        val added: Long,
        val label: String,
    )

    private val entryOrder: Comparator<Entry> = Comparator { x, y ->
        when {
            x.inProgress != y.inProgress -> if (x.inProgress) -1 else 1
            x.inProgress && x.recency != y.recency -> y.recency.compareTo(x.recency)
            !x.inProgress && x.added != y.added -> y.added.compareTo(x.added)
            else -> naturalCompare(x.label, y.label)
        }
    }

    private val volumeOrder: Comparator<Book> =
        compareBy<Book, Double?>(nullsLast()) { it.seriesIndex }
            .thenBy(naturalOrder) { it.file.name }

    private fun recency(b: Book): Long = (b.lastOpened ?: 0L) * 1000

    private fun bookUnits(books: List<Book>): List<Entry> {
        val out = ArrayList<Entry>()
        val bySeries = LinkedHashMap<String, MutableList<Book>>()
        for (b in books) {
            val key = b.series?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            if (key == null) out += singleBook(b) else bySeries.getOrPut(key) { ArrayList() } += b
        }
        for (members in bySeries.values) {
            if (members.size == 1) { out += singleBook(members[0]); continue }
            val sorted = members.sortedWith(volumeOrder)
            val items = sorted.map { BookItem(it, isInProgress(it)) }
            val reading = sorted.filter { isInProgress(it) }
            val name = sorted.first().series!!.trim()
            out += Entry(
                item = SeriesGroup(name, items),
                inProgress = reading.isNotEmpty(),
                recency = reading.maxOfOrNull { recency(it) } ?: 0L,
                added = sorted.maxOf { it.addedAt },
                label = name,
            )
        }
        return out
    }

    private fun singleBook(b: Book): Entry {
        val p = isInProgress(b)
        return Entry(BookItem(b, p), p, recency(b), b.addedAt, b.title)
    }

    private fun mangaUnit(m: Manga, nowMs: Long): Entry {
        val p = isInProgress(m, nowMs)
        val caughtUp = p && m.chaptersRead >= m.chaptersTotal
        return Entry(MangaItem(m, p, caughtUp), p, m.lastReadAt, m.dateAdded, m.title)
    }

    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply {
        strength = Collator.SECONDARY // ignore case, keep accents/ё distinct
    }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isDigit()
            val bDigit = b[j].isDigit()
            val ie = runEnd(a, i, aDigit)
            val je = runEnd(b, j, bDigit)
            val c = if (aDigit && bDigit) {
                compareNumbers(a.substring(i, ie), b.substring(j, je))
            } else {
                collator.compare(a.substring(i, ie), b.substring(j, je))
            }
            if (c != 0) return c
            i = ie
            j = je
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun runEnd(s: String, start: Int, digits: Boolean): Int {
        var k = start
        while (k < s.length && s[k].isDigit() == digits) k++
        return k
    }

    /** Numeric compare of two digit runs without overflow: strip leading zeros, then length, then text. */
    private fun compareNumbers(x: String, y: String): Int {
        val xs = x.trimStart('0')
        val ys = y.trimStart('0')
        if (xs.length != ys.length) return xs.length.compareTo(ys.length)
        val c = xs.compareTo(ys)
        return if (c != 0) c else x.length.compareTo(y.length)
    }
}
