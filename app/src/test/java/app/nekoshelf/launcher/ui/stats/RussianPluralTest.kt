package app.nekoshelf.launcher.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianPluralTest {
    private fun form(n: Int) = russianPlural(n, "one", "few", "many")

    @Test fun formsFollowTheLastTwoDigits() {
        assertEquals(listOf("many", "one", "few", "few", "few", "many"), (0..5).map(::form))
        assertEquals(listOf("many", "many", "many", "many"), listOf(11, 12, 14, 19).map(::form))
        assertEquals(listOf("one", "few", "many", "one", "many"), listOf(21, 22, 25, 101, 111).map(::form))
    }
}
