package app.meebook.launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SpineTitleTest {
    @Test
    fun seriesPrefixIsStrippedInsideAGroup() {
        assertEquals(
            "Том 26 - Молодость",
            spineTitle("Реинкарнация Безработного - Том 26 - Молодость", "Реинкарнация безработного", 26.0),
        )
        assertEquals("2: Курортная ночь", spineTitle("Пикник в потусторонье 2: Курортная ночь", "Пикник в потусторонье", null))
    }

    @Test
    fun volumeNumberIsAddedWhenTheTitleDoesNotStartWithTheSeries() {
        assertEquals("3 · Присутствие Яманокэ", spineTitle("Присутствие Яманокэ", "Пикник в потусторонье", 3.0))
        assertEquals("2.5 · Интерлюдия", spineTitle("Интерлюдия", "Серия", 2.5))
    }

    @Test
    fun titleKeptWhenNothingIsLeftOrNoSeries() {
        assertEquals("Пикник в потусторонье", spineTitle("Пикник в потусторонье", "Пикник в потусторонье", null))
        assertEquals("Книга", spineTitle("Книга", null, null))
    }
}
