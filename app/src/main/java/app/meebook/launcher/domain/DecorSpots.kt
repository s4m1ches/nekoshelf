package app.meebook.launcher.domain

import kotlin.random.Random

/** Number of places along the header's top strip where a catgirl can stand. */
const val DECOR_SLOTS = 5

/** One catgirl on the header strip: asset [decor] standing in place [slot] (0 = leftmost). */
data class DecorSpot(val decor: String, val slot: Int)

/**
 * Picks this visit's catgirls: 1..[DECOR_SLOTS] of them (never more than there are figures),
 * all different, each in its own randomly chosen place. Figures are taken in turn from
 * [all] starting at [next], so every figure gets shown before any repeats.
 * Returns the spots sorted by slot and the index to start from next time.
 */
fun pickDecorSpots(all: List<String>, next: Int, random: Random): Pair<List<DecorSpot>, Int> {
    if (all.isEmpty()) return emptyList<DecorSpot>() to 0
    val count = random.nextInt(1, minOf(DECOR_SLOTS, all.size) + 1)
    val start = next.mod(all.size)
    val slots = (0 until DECOR_SLOTS).shuffled(random).take(count)
    val spots = slots.mapIndexed { i, slot -> DecorSpot(all[(start + i) % all.size], slot) }
    return spots.sortedBy { it.slot } to (start + count) % all.size
}
