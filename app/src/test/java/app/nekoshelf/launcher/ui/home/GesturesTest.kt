package app.nekoshelf.launcher.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ruling R13: a swipe that starts on a card must never end up as a tap on that card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GesturesTest {
    @get:Rule val rule = createComposeRule()

    private val clicks = mutableIntStateOf(0)
    private val ups = mutableIntStateOf(0)
    private val otherUps = mutableIntStateOf(0)

    /** A 300 dp screen with swipe-up detection holding a 200 dp guarded card in the middle. */
    private fun setHome(swapCallback: androidx.compose.runtime.MutableState<Boolean>? = null, guard: Boolean = true) {
        rule.setContent {
            val onUp: () -> Unit = if (swapCallback?.value == true) ({ otherUps.intValue++ }) else ({ ups.intValue++ })
            Box(Modifier.size(300.dp).swipes(onUp = onUp).testTag("screen")) {
                Box(
                    Modifier.align(Alignment.Center).size(200.dp).testTag("card")
                        .then(if (guard) Modifier.noTapAfterSlop() else Modifier)
                        .clickable { clicks.intValue++ },
                )
            }
        }
    }

    @Test
    fun aTapOnTheCardClicks() {
        setHome()
        rule.onNodeWithTag("card").performTouchInput { down(center); up() }
        rule.runOnIdle {
            assertEquals(1, clicks.intValue)
            assertEquals(0, ups.intValue)
        }
    }

    @Test
    fun aSwipeUpStartingOnTheCardOpensAppsAndNotTheCard() {
        setHome()
        rule.onNodeWithTag("card").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -15.dp.toPx())) }
            up()
        }
        rule.runOnIdle {
            assertEquals(0, clicks.intValue)
            assertEquals(1, ups.intValue)
        }
    }

    @Test
    fun aPressThatMovedPastSlopInsideTheCardIsNoClickEvenWithoutAParentDetector() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(200.dp).testTag("card").noTapAfterSlop().clickable { clicks.intValue++ })
            }
        }
        rule.onNodeWithTag("card").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx())) // still inside the card
            up()
        }
        rule.runOnIdle { assertEquals(0, clicks.intValue) }
    }

    @Test
    fun withoutTheGuardThatSamePressWouldClick() {
        // Why the guard exists: a plain clickable fires after an in-bounds drag past slop.
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(200.dp).testTag("card").clickable { clicks.intValue++ })
            }
        }
        rule.onNodeWithTag("card").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            up()
        }
        rule.runOnIdle { assertEquals(1, clicks.intValue) }
    }

    @Test
    fun aReleaseFarAwayInOneJumpIsNoClick() {
        // Sparse e-ink touch reports: down, then the next event is already the far release point.
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(200.dp).testTag("card").noTapAfterSlop().clickable { clicks.intValue++ })
            }
        }
        rule.onNodeWithTag("card").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - 60.dp.toPx()))
            up()
        }
        rule.runOnIdle { assertEquals(0, clicks.intValue) }
    }

    @Test
    fun aRecompositionMidSwipeDoesNotRestartTheDetector() {
        val swap = mutableStateOf(false)
        setHome(swapCallback = swap)
        val card = rule.onNodeWithTag("card")
        card.performTouchInput {
            down(center)
            moveBy(Offset(0f, -4.dp.toPx())) // within slop
        }
        // New callback lambda while the finger is down (e.g. a «Манга» change reloaded the data).
        rule.runOnIdle { swap.value = true }
        rule.waitForIdle()
        card.performTouchInput {
            repeat(10) { moveBy(Offset(0f, -15.dp.toPx())) }
            up()
        }
        rule.runOnIdle {
            assertEquals("the tap under the swipe must not fire", 0, clicks.intValue)
            assertEquals("the swipe still counts, with the current callback", 1, otherUps.intValue)
            assertEquals(0, ups.intValue)
        }
    }

    @Test
    fun slopAndDirectionDecisions() {
        assertFalse(movedPastSlop(Offset(3f, 4f), 5f))
        assertTrue(movedPastSlop(Offset(3f, 4.1f), 5f))
        assertEquals(SwipeDirection.UP, swipeDirection(Offset(10f, -200f), 150f))
        assertEquals(SwipeDirection.DOWN, swipeDirection(Offset(-10f, 200f), 150f))
        assertEquals(SwipeDirection.LEFT, swipeDirection(Offset(-200f, 20f), 150f))
        assertEquals(SwipeDirection.RIGHT, swipeDirection(Offset(200f, -20f), 150f))
        assertNull("too short", swipeDirection(Offset(0f, -100f), 150f))
        assertNull("too diagonal", swipeDirection(Offset(180f, -200f), 150f))
    }
}
