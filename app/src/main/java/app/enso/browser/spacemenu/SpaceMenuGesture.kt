package app.enso.browser.spacemenu

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Opens the space menu after a hold in a trigger zone (spec: board 3), seeing every touch before the views do.
 * It has to sit above the views: GeckoView blocks parent interception, so a parent never sees page gestures.
 *
 * Until the hold fires, touches pass through untouched, so taps and scrolls in a trigger zone work as before.
 * Once it fires, the views get a cancel and the rest of the touch drives the menu.
 */
class SpaceMenuGesture(
    private val host: View,
    private val holdMs: () -> Long,
    /** Whether a hold at this point opens the menu: inside a trigger zone, and nothing blocking it. */
    private val opensAt: (x: Float, y: Float) -> Boolean,
    /** Sends an event to the views, skipping this gesture. */
    private val dispatchToViews: (MotionEvent) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        /** The hold fired at [touch]. False when the menu can't open (nothing to switch to, say). */
        fun onOpen(touch: Offset): Boolean

        fun onMove(finger: Offset)

        fun onRelease()

        fun onCancel()
    }

    private class Pending(val downTime: Long, val pointerId: Int, val x: Float, val y: Float)

    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var pending: Pending? = null

    /** The pointer driving the open menu, or null when it's closed. */
    private var menuPointer: Int? = null

    private val hold = Runnable {
        val held = pending ?: return@Runnable
        pending = null
        if (!listener.onOpen(Offset(held.x, held.y))) return@Runnable
        menuPointer = held.pointerId
        val cancel = MotionEvent.obtain(held.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, held.x, held.y, 0)
        dispatchToViews(cancel)
        cancel.recycle()
    }

    /** True when the touch belongs to the menu and the views mustn't see it. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        // A new touch while the menu thinks it's open means its touch ended unseen; start over.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) reset()
        menuPointer?.let { pointer ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> event.findPointerIndex(pointer).takeIf { it >= 0 }?.let { index ->
                    listener.onMove(Offset(event.getX(index), event.getY(index)))
                }
                MotionEvent.ACTION_UP -> close { listener.onRelease() }
                MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) close { listener.onRelease() }
                MotionEvent.ACTION_CANCEL -> close { listener.onCancel() }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (opensAt(event.x, event.y)) {
                    pending = Pending(event.downTime, event.getPointerId(0), event.x, event.y)
                    host.postDelayed(hold, holdMs())
                }
            }
            MotionEvent.ACTION_MOVE -> pending?.let { held ->
                val index = event.findPointerIndex(held.pointerId)
                if (index < 0 || hypot(event.getX(index) - held.x, event.getY(index) - held.y) > touchSlop) reset()
            }
            // A second finger, a lift or a cancel before the hold fires: it wasn't a hold.
            else -> reset()
        }
        return false
    }

    /** Forgets a hold in progress and cancels an open menu, for example when the app goes to the background. */
    fun reset() {
        pending = null
        host.removeCallbacks(hold)
        if (menuPointer != null) close { listener.onCancel() }
    }

    private fun close(then: () -> Unit) {
        menuPointer = null
        then()
    }
}

/** Overlays that keep a hold from opening the space menu while they're up, such as menus and the search palette. */
class SpaceMenuBlocks {
    var count = 0
        internal set
}

val LocalSpaceMenuBlocks = staticCompositionLocalOf { SpaceMenuBlocks() }

/** While this is in the composition, holds don't open the space menu. */
@Composable
fun BlockSpaceMenu() {
    val blocks = LocalSpaceMenuBlocks.current
    DisposableEffect(blocks) {
        blocks.count++
        onDispose { blocks.count-- }
    }
}

/**
 * The trigger zones (spec: board 3): a strip up the wave's edge from the bottom to the top bar (the right edge,
 * or the left in left-handed mode), and the address field while it shows.
 *
 * The strip can be taller than the 200 dp per edge that Android exempts from its gestures, because the hold
 * itself is what keeps the back gesture from firing. Where Samsung's Edge panel handle sits on the edge, its
 * system window takes the touches and the app never sees them.
 */
object SpaceMenuZones {
    private val EdgeWidth = 28.dp

    /**
     * Whether ([x], [y]) is in a zone, on a screen [width] wide. The strip runs from [stripTop] down to [bottom],
     * where the usable screen ends.
     */
    fun contains(
        x: Float,
        y: Float,
        width: Float,
        stripTop: Float,
        bottom: Float,
        addressField: Rect?,
        leftHanded: Boolean,
        density: Density,
    ): Boolean {
        val inStrip = with(density) {
            y in stripTop..bottom && if (leftHanded) x <= EdgeWidth.toPx() else x >= width - EdgeWidth.toPx()
        }
        return inStrip || addressField?.contains(Offset(x, y)) == true
    }
}
