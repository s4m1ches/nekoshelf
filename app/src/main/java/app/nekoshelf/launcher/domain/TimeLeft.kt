package app.nekoshelf.launcher.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Total reading time and pages of one book (or one entry of KOReader's `book` table). */
data class ReadSpeed(val seconds: Long, val pages: Long)

private const val MIN_PAGES = 20L

/**
 * Estimated seconds to finish a book: pages left × seconds per page. Uses the book's own speed
 * once it has at least [MIN_PAGES] read pages, else the average over [all] books (also needing
 * that many pages); null without enough data or when nothing is left.
 */
fun secondsLeft(pages: Int?, percent: Double?, book: ReadSpeed?, all: Collection<ReadSpeed>): Long? {
    if (pages == null || percent == null || pages <= 0) return null
    val speed = book?.takeIf { it.pages >= MIN_PAGES && it.seconds > 0 } ?: run {
        val valid = all.filter { it.pages > 0 }
        val p = valid.sumOf { it.pages }
        val s = valid.sumOf { it.seconds }
        if (p >= MIN_PAGES && s > 0) ReadSpeed(s, p) else null
    } ?: return null
    val left = (pages * (1 - percent.coerceIn(0.0, 1.0))).roundToLong()
    if (left <= 0) return null
    return left * speed.seconds / speed.pages
}

/** «~40 мин», «~2 ч 40 мин», «~12 ч»: <1 ч to 5 min, 1–10 ч to 10 min, more to the hour. */
fun formatLeft(seconds: Long): String {
    val min = seconds / 60.0
    if (min < 60) {
        val m = ((min / 5).roundToInt() * 5).coerceAtLeast(5)
        if (m < 60) return "~$m мин"
    }
    if (min < 600) {
        val t = (min / 10).roundToInt() * 10
        val h = t / 60
        val m = t % 60
        return if (m == 0) "~$h ч" else "~$h ч $m мин"
    }
    return "~${(min / 60).roundToInt()} ч"
}
