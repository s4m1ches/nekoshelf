package app.meebook.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Detects flings in four directions without animating anything. Once a pointer has moved past
 * the touch slop the gesture is consumed, so taps on children (which check consumption in the
 * final pass) are cancelled rather than fired at the end of a swipe. Nested detectors all see
 * the movement because consumption is ignored here; each one acts only on its own directions.
 * A swipe must travel [minDistance] and be mostly along one axis.
 */
internal fun Modifier.swipes(
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    minDistance: androidx.compose.ui.unit.Dp = 56.dp,
): Modifier = pointerInput(onUp, onDown, onLeft, onRight) {
    val slop = viewConfiguration.touchSlop
    val min = minDistance.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var delta = Offset.Zero
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            delta = change.position - down.position
            if (!dragging && delta.getDistance() > slop) dragging = true
            if (dragging) change.consume()
            if (!change.pressed) break
        }
        if (!dragging) return@awaitEachGesture
        val (dx, dy) = delta
        if (abs(dy) > abs(dx) * 1.3f && abs(dy) >= min) {
            if (dy < 0) onUp?.invoke() else onDown?.invoke()
        } else if (abs(dx) > abs(dy) * 1.3f && abs(dx) >= min) {
            if (dx < 0) onLeft?.invoke() else onRight?.invoke()
        }
    }
}
