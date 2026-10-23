package app.meebook.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DecorSpotsTest {
    private val figures = (1..48).map { "neko_%02d.png".format(it) }

    @Test
    fun oneToFiveDistinctFiguresInDistinctSlots() {
        val counts = HashSet<Int>()
        repeat(500) { seed ->
            val (spots, _) = pickDecorSpots(figures, seed, Random(seed))
            counts += spots.size
            assertTrue(spots.size in 1..DECOR_SLOTS)
            assertEquals(spots.size, spots.map { it.decor }.toSet().size)
            assertEquals(spots.size, spots.map { it.slot }.toSet().size)
            assertTrue(spots.all { it.slot in 0 until DECOR_SLOTS })
            assertEquals(spots.sortedBy { it.slot }, spots)
        }
        assertEquals((1..DECOR_SLOTS).toSet(), counts)
    }

    @Test
    fun figuresAreTakenInTurnAndWrapAround() {
        val (spots, next) = pickDecorSpots(figures, 46, Random(3))
        val taken = spots.map { figures.indexOf(it.decor) }.sorted()
        val expected = (46 until 46 + spots.size).map { it % figures.size }.sorted()
        assertEquals(expected, taken)
        assertEquals((46 + spots.size) % figures.size, next)
    }

    @Test
    fun fewFiguresNeverRepeatOnOneVisit() {
        repeat(100) { seed ->
            val (spots, _) = pickDecorSpots(listOf("a.png", "b.png"), seed, Random(seed))
            assertTrue(spots.size in 1..2)
            assertEquals(spots.size, spots.map { it.decor }.toSet().size)
        }
    }

    @Test
    fun noFiguresNoSpots() {
        assertEquals(emptyList<DecorSpot>() to 0, pickDecorSpots(emptyList(), 7, Random(1)))
    }
}
