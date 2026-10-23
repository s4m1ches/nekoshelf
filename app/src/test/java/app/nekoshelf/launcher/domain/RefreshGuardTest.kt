package app.nekoshelf.launcher.domain

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RefreshGuardTest {
    @Test
    fun dayChangedOnlyWhenTheDateDiffers() {
        val d = LocalDate.of(2026, 10, 23)
        assertFalse(RefreshGuard.dayChanged(d, d))
        assertTrue(RefreshGuard.dayChanged(d, d.plusDays(1)))
        assertTrue(RefreshGuard.dayChanged(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun aThrowingLoadIsCaughtWhereItIsAwaited() = runBlocking {
        val failures = mutableListOf<Exception>()
        val ok = RefreshGuard.run(onFailure = { failures += it }) {
            val load = async<Int> { throw IllegalStateException("boom") }
            load.await()
        }
        assertFalse(ok)
        assertEquals(1, failures.size)
        assertEquals("boom", failures[0].message)
    }

    @Test
    fun aCompletedBodyReturnsTrue() = runBlocking {
        assertTrue(RefreshGuard.run(onFailure = { fail() }) { async { 1 }.await() })
    }

    @Test
    fun cancellationIsRethrown() {
        var reported = false
        try {
            runBlocking { RefreshGuard.run(onFailure = { reported = true }) { throw CancellationException("x") } }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertFalse(reported)
        }
    }
}
