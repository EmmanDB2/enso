package app.enso.browser.tabsheet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** Dragging a tab out of the sidebar or rail onto the page, to split them (board 7). Fingers are in window pixels. */
interface TabDragActions {
    fun start(tabId: String, finger: Offset)

    fun move(finger: Offset)

    fun drop(finger: Offset)

    /** The drag ended without a drop, as when its row went away mid-drag. */
    fun cancel()
}

/**
 * Hold a tab, then move: the row's own long press opens its menu at the hold, and moving on drags the tab instead.
 * This watches the touch before the row and its swipe do (the initial pass), leaves taps, swipes and scrolls that
 * move before the hold to them, and takes the touch once it drags.
 */
internal fun Modifier.dragAfterHold(tabId: String, drag: TabDragActions?): Modifier = if (drag == null) this else composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val actions by rememberUpdatedState(drag)
    onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(tabId) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val beforeHold = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull
                        if (!change.pressed || (change.position - down.position).getDistance() > slop) return@withTimeoutOrNull
                    }
                }
                if (beforeHold != null) return@awaitEachGesture
                var dragging = false
                var ended = false
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val finger = origin + change.position
                        if (!change.pressed) {
                            if (dragging) actions.drop(finger)
                            ended = true
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                            dragging = true
                            actions.start(tabId, finger)
                        }
                        if (dragging) {
                            change.consume()
                            actions.move(finger)
                        }
                    }
                } finally {
                    if (dragging && !ended) actions.cancel()
                }
            }
        }
}
