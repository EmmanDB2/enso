package app.enso.browser.toolbar

import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mozilla.components.concept.engine.EngineView

/**
 * The page view as the bars' scrolling behaviors see it, with scroll deltas they can't miss.
 *
 * Android Components keeps its deltas in a `StateFlow`, which drops a value equal to the last one. A slow, steady
 * scroll moves the page the same few pixels every frame, so nearly every delta was lost: the bars hardly moved, then
 * snapped back on release, and the page, which the strip under the status bar moves, jittered. These deltas come from
 * the scroll position instead (scrollY × zoom), so a dropped repeat of the position loses nothing.
 *
 * Small turns against the scroll's direction are held back until they add up to [REVERSE_SLOP]. The strip moves the
 * page under the finger, and Gecko answers each pixel it moves with a pixel of scroll the other way, which made the
 * strip (and the page with it) step back and forth during a slow scroll.
 *
 * Like Android Components' own, they pause while the page zooms: [zooming] says when two fingers are down.
 */
class SteadyScrollDeltas(private val view: EngineView, private val zooming: () -> Boolean) : EngineView by view {
    override val verticalScrollDelta: Flow<Float> = flow {
        // Each scroll gesture collects afresh, so its first position only sets where it starts.
        var last: Float? = null
        var direction = 0f
        var heldBack = 0f
        view.verticalScrollPosition.collect { position ->
            val delta = last?.let { position - it }
            last = position
            if (delta == null || delta == 0f || zooming()) return@collect
            if (direction == 0f || delta.sign == direction) {
                direction = delta.sign
                heldBack = 0f
                emit(delta)
            } else {
                heldBack += delta
                if (abs(heldBack) >= REVERSE_SLOP) {
                    direction = heldBack.sign
                    emit(heldBack)
                    heldBack = 0f
                }
            }
        }
    }

    private companion object {
        /** Pixels of scroll against the direction so far that it takes to turn the bars round. */
        const val REVERSE_SLOP = 8f
    }
}
