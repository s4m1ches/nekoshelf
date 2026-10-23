package app.nekoshelf.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Detects flings in four directions without animating anything. Once a pointer has moved past
 * the touch slop the gesture is consumed, so taps on children (which check consumption in the
 * final pass) are cancelled rather than fired at the end of a swipe. Nested detectors all see
 * the movement because consumption is ignored here; each one acts only on its own directions.
 * A swipe must travel [minDistance] and be mostly along one axis.
 *
 * The detector is keyed on nothing and reads the callbacks through rememberUpdatedState
 * (ruling R13): a recomposition that hands in new lambdas must not restart the detector in the
 * middle of a swipe — a restarted detector stops consuming, and the tap underneath would fire.
 */
internal fun Modifier.swipes(
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    minDistance: Dp = 56.dp,
): Modifier = composed {
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val min by rememberUpdatedState(minDistance)
    pointerInput(Unit) {
        awaitEachGesture {
            val slop = viewConfiguration.touchSlop
            val first = awaitFirstDown(requireUnconsumed = false)
            var delta = Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                delta = change.position - first.position
                if (!dragging && movedPastSlop(delta, slop)) dragging = true
                if (dragging) change.consume()
                if (!change.pressed) break
            }
            if (!dragging) return@awaitEachGesture
            when (swipeDirection(delta, min.toPx())) {
                SwipeDirection.UP -> up?.invoke()
                SwipeDirection.DOWN -> down?.invoke()
                SwipeDirection.LEFT -> left?.invoke()
                SwipeDirection.RIGHT -> right?.invoke()
                null -> Unit
            }
        }
    }
}

/**
 * Put before a `clickable` / `combinedClickable` on a card, cover or spine: once the pointer
 * has moved past the touch slop from where it went down, every later change of that press is
 * consumed in the Initial pass — before the clickable sees it — so the press can no longer end
 * as a click (or long-click), even if the panel reports the whole swipe as one jump to the
 * release point. Swipe detectors ignore consumption, so they still see the movement.
 */
internal fun Modifier.noTapAfterSlop(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val slop = viewConfiguration.touchSlop
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == first.id } ?: break
            if (!moved && movedPastSlop(change.position - first.position, slop)) moved = true
            if (moved) change.consume()
            if (!change.pressed) break
        }
    }
}

internal enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** True once a press has travelled farther than [slop] px from where it went down. */
internal fun movedPastSlop(delta: Offset, slop: Float): Boolean = delta.getDistance() > slop

/** The swipe a finished drag of [delta] px means, or null (too short, or too diagonal). */
internal fun swipeDirection(delta: Offset, minPx: Float): SwipeDirection? {
    val (dx, dy) = delta
    return when {
        abs(dy) > abs(dx) * 1.3f && abs(dy) >= minPx -> if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
        abs(dx) > abs(dy) * 1.3f && abs(dx) >= minPx -> if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
        else -> null
    }
}
