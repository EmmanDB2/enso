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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.toolbar.LoadProgress
import app.enso.browser.toolbar.TopBarState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.SpaceColors
import kotlin.math.roundToInt

/**
 * A page card on the inner screen, in window pixels: where it's drawn, and where its page view is. They differ
 * while an edge moves, since the pages only resize once it stops.
 */
data class PageCard(val card: Rect, val page: Rect = card)

/**
 * The inner screen's frame (boards 5–7): the space's colors everywhere but the page cards, which show their
 * pages through rounded holes. The focused card has the load line along its top edge, and in a split an accent
 * outline too.
 *
 * GeckoView draws into its own surface, which ignores view clipping, so the cards' rounded corners are this
 * frame painted over the pages' square ones. The frame has no touch handling, so touches on a card reach it.
 */
@Composable
fun InnerFrame(colors: SpaceColors, cards: List<PageCard>, focused: Int, page: TopBarState, corner: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = CornerRadius(corner.toPx())
        val frame = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            cards.forEach { addRoundRect(RoundRect(it.card, radius)) }
        }
        drawPath(frame, colors.brush(top = 0f, windowHeight = size.height))
        // Where a card reaches past its page for now (a split's divider moving), it shows blank page rather than
        // the page next door.
        cards.forEach { (card, view) ->
            val covered = view.left <= card.left && view.top <= card.top && view.right >= card.right && view.bottom >= card.bottom
            if (!covered) {
                val blank = Path.combine(
                    PathOperation.Difference,
                    Path().apply { addRoundRect(RoundRect(card, radius)) },
                    Path().apply { addRect(view) },
                )
                drawPath(blank, EnsoColors.Page)
            }
        }
        val outlined = cards.getOrNull(focused)?.card?.takeIf { cards.size > 1 }
        if (outlined != null) {
            val stroke = OutlineWidth.toPx()
            drawRoundRect(
                color = colors.accent,
                topLeft = outlined.topLeft + Offset(stroke / 2, stroke / 2),
                size = Size(outlined.width - stroke, outlined.height - stroke),
                cornerRadius = CornerRadius((radius.x - stroke / 2).coerceAtLeast(0f)),
                style = Stroke(stroke),
            )
        }
    }
    val card = cards.getOrNull(focused)?.card ?: return
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

/** The focused page's outline in a split (board 7). */
private val OutlineWidth = 2.dp
