package app.enso.browser.sidebar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.toolbar.LoadProgress
import app.enso.browser.toolbar.TopBarState
import app.enso.browser.ui.SpaceColors
import kotlin.math.roundToInt

/**
 * The inner screen's frame (boards 5 and 6): the space's colors everywhere but the page card, which shows the
 * page through a rounded hole, with the load line along the card's top edge.
 *
 * GeckoView draws into its own surface, which ignores view clipping, so the card's rounded corners are this
 * frame painted over the page's square ones. The frame has no touch handling, so touches on the card reach it.
 */
@Composable
fun InnerFrame(colors: SpaceColors, card: Rect, page: TopBarState, corner: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val frame = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(card, CornerRadius(corner.toPx())))
        }
        drawPath(frame, colors.brush(top = 0f, windowHeight = size.height))
    }
    if (card.isEmpty) return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(card.left.roundToInt(), card.top.roundToInt()) }
            .size(with(density) { card.width.toDp() }, with(density) { card.height.toDp() })
            .clip(RoundedCornerShape(corner)),
    ) {
        LoadProgress(page, colors.accent)
    }
}

/** The page card's corner radius, kept small to suit its thin border. Square when the border is off. */
val CardCorner: Dp = 10.dp
