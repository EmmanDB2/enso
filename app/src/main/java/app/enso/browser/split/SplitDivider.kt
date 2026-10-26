package app.enso.browser.split

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import kotlin.math.roundToInt

/**
 * The gap between two pages of a split (board 7), [gap] in window pixels. Dragging it trades length between the
 * pages ([onDrag] gets the change in pixels): sideways, or up and down when the pages are stacked ([across]).
 * Holding it still makes the pages equal again. It reaches a little into both pages, so it's easy to grab.
 */
@Composable
fun SplitDivider(
    gap: Rect,
    across: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val reach = with(density) { SplitDividerReach.toPx() }
    var pressed by remember { mutableStateOf(false) }
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val reset by rememberUpdatedState(onReset)
    // The divider moves with the drag, so the finger is tracked in window coordinates (as the sidebar's edge is).
    var handleAt by remember { mutableFloatStateOf(0f) }
    val label = stringResource(R.string.split_divider)
    val area = if (across) {
        Rect(gap.left, gap.top - reach, gap.right, gap.bottom + reach)
    } else {
        Rect(gap.left - reach, gap.top, gap.right + reach, gap.bottom)
    }
    Box(
        Modifier
            .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
            .onGloballyPositioned { handleAt = it.positionInWindow().let { at -> if (across) at.y else at.x } }
            .semantics { contentDescription = label }
            .pointerInput(across) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    val along = { change: PointerInputChange -> handleAt + if (across) change.position.y else change.position.x }
                    var last = along(down)
                    var moved: PointerInputChange? = null
                    // Held still for a long press: back to equal. Moved first: a drag.
                    val heldStill = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        moved = if (across) {
                            awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        } else {
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        }
                    } == null
                    val first = moved
                    when {
                        heldStill -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            reset()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                        first != null -> {
                            val follow = { change: PointerInputChange ->
                                change.consume()
                                val at = along(change)
                                drag(at - last)
                                last = at
                            }
                            start()
                            follow(first)
                            if (across) verticalDrag(first.id, follow) else horizontalDrag(first.id, follow)
                            end()
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) {
            Box(
                Modifier
                    .then(if (across) Modifier.width(36.dp).height(Grabber) else Modifier.width(Grabber).height(36.dp))
                    .background(EnsoColors.TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** How far into each page the divider's touch area reaches. */
val SplitDividerReach = 10.dp
private val Grabber = 3.dp
