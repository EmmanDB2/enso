package app.enso.browser.bottombar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.spaces.Tab
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.SpaceColors
import app.enso.browser.ui.spaceSurface
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The cover's tab flip, after Arc Search: a sideways drag on the closed bar takes the page with the thumb as a card,
 * over the card of the tab used before it. Letting go past a third of the screen, or with a flick, finishes the flip;
 * otherwise the card springs back. The activity drives [offset] and [alpha]; [TabFlipLayer] draws it.
 */
class TabFlip(val fromTabId: String, val toTabId: String, val walk: Any? = null, val step: Int = 0) {
    /** The page on screen as the drag began, and where it was. Nothing shows until it arrives. */
    var capture by mutableStateOf<Pair<ImageBitmap, Rect>?>(null)

    /** How far the page's card has gone sideways, in pixels. */
    var offset by mutableFloatStateOf(0f)

    /** The whole layer's opacity: it fades once the flip is done and the new page is under it. */
    var alpha by mutableFloatStateOf(1f)

    /** The drag has ended and the card is springing back or away. Until then a new drag may replace a lost one. */
    var settling = false

    /** Letting go finishes this flip; [committed] once its tab is selected. */
    var finishing = false
    var committed = false

    /**
     * Where the page is, for a flip that follows another so closely that the page hasn't drawn yet: its card is then
     * the tab's thumbnail rather than a capture.
     */
    var bounds: Rect? = null
}

/** How small the card underneath starts, growing to full size as the page's card leaves. */
private const val UNDER_SCALE = 0.92f

/**
 * The flip as it's drawn, over the page and under the bar: the space's colors, the last tab's card (its thumbnail, or
 * its favicon until it has one) and the page's card sliding over it. At rest the page's card covers the page exactly,
 * so the layer appears without a jump; its corners round as it starts to move.
 */
@Composable
internal fun TabFlipLayer(flip: TabFlip, colors: SpaceColors, thumbnails: ThumbnailStorage, toTab: Tab?) {
    val bounds = flip.capture?.second ?: flip.bounds ?: return
    val image = flip.capture?.first
    val density = LocalDensity.current
    val width = bounds.width.coerceAtLeast(1f)
    val progress = (abs(flip.offset) / width).coerceIn(0f, 1f)
    val card = with(density) {
        Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(bounds.width.toDp(), bounds.height.toDp())
    }
    val corner = 16.dp
    // The corners round over the first 48 dp of travel.
    val cornerShown = with(density) { (abs(flip.offset) / 48.dp.toPx()).coerceAtMost(1f) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = flip.alpha }.spaceSurface(colors)) {
        Box(
            card
                .graphicsLayer {
                    val scale = UNDER_SCALE + (1f - UNDER_SCALE) * progress
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(corner * (1f - progress))
                    clip = true
                }
                .background(EnsoColors.Card),
            contentAlignment = Alignment.Center,
        ) {
            TabThumbnail(flip.toTabId, thumbnails, Modifier.fillMaxSize(), sizePx = bounds.width.roundToInt()) {
                toTab?.let { Favicon(it, size = 40.dp) }
            }
            // A shade that lifts as it comes forward.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f * (1f - progress))))
        }
        val top = card.graphicsLayer {
            translationX = flip.offset
            shadowElevation = 24.dp.toPx() * cornerShown
            shape = RoundedCornerShape(corner * cornerShown)
            clip = true
        }
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = top)
        } else {
            Box(top.background(EnsoColors.Card)) {
                TabThumbnail(flip.fromTabId, thumbnails, Modifier.fillMaxSize(), sizePx = bounds.width.roundToInt()) {}
            }
        }
    }
}
