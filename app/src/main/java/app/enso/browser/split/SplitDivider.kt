package app.enso.browser.split

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
 * The gap between two pages of a split (board 7), at [gapLeft] to [gapRight] in window pixels. Dragging it sideways
 * trades width between the pages ([onDrag] gets the change in pixels); holding it still makes the pages equal
 * again. It reaches a little into both pages, so it's easy to grab.
 */
@Composable
fun SplitDivider(
    gapLeft: Float,
    gapRight: Float,
    top: Float,
    bottom: Float,
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
    var handleX by remember { mutableFloatStateOf(0f) }
    val label = stringResource(R.string.split_divider)
    Box(
        Modifier
            .offset { IntOffset((gapLeft - reach).roundToInt(), top.roundToInt()) }
            .size(with(density) { (gapRight - gapLeft + 2 * reach).toDp() }, with(density) { (bottom - top).toDp() })
            .onGloballyPositioned { handleX = it.positionInWindow().x }
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    var lastX = handleX + down.position.x
                    var moved: PointerInputChange? = null
                    // Held still for a long press: back to equal. Moved first: a drag.
                    val heldStill = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        moved = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
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
                                val x = handleX + change.position.x
                                drag(x - lastX)
                                lastX = x
                            }
                            start()
                            follow(first)
                            horizontalDrag(first.id, follow)
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
                    .width(Grabber)
                    .height(36.dp)
                    .background(EnsoColors.TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** How far into each page the divider's touch area reaches. */
val SplitDividerReach = 10.dp
private val Grabber = 3.dp
