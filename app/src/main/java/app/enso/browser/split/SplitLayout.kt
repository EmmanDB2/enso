package app.enso.browser.split

import androidx.compose.ui.geometry.Rect
import app.enso.browser.spaces.SplitLayout

// Where a split's pages go (board 7), as Zen's split view lays them out. All in window pixels.

/** Each page's share of the split's length: [sizes] when they fit [count] pages, else equal shares. */
fun splitShares(sizes: List<Float>, count: Int): List<Float> =
    sizes.takeIf { it.size == count && it.all { share -> share > 0f } } ?: List(count) { 1f / count }

/**
 * [count] pages in [card], [gap] apart: in a row, in a column (both sized by [sizes]), or a grid of two equal
 * columns with the first two pages on the left (as Zen's grid; two pages sit side by side).
 */
fun paneRects(card: Rect, count: Int, sizes: List<Float>, gap: Float, layout: SplitLayout = SplitLayout.SideBySide): List<Rect> {
    if (count <= 1) return listOf(card)
    return when {
        layout == SplitLayout.Stacked -> column(card, splitShares(sizes, count), gap)
        layout == SplitLayout.Grid && count > 2 -> {
            val (left, right) = row(card, listOf(0.5f, 0.5f), gap)
            column(left, List(2) { 0.5f }, gap) + column(right, List(count - 2) { 1f / (count - 2) }, gap)
        }
        layout == SplitLayout.Grid -> row(card, List(count) { 1f / count }, gap)
        else -> row(card, splitShares(sizes, count), gap)
    }
}

private fun row(card: Rect, shares: List<Float>, gap: Float): List<Rect> {
    val total = shares.sum()
    val width = card.width - gap * (shares.size - 1)
    var left = card.left
    return shares.mapIndexed { index, share ->
        val right = if (index == shares.lastIndex) card.right else left + width * share / total
        Rect(left, card.top, right, card.bottom).also { left = right + gap }
    }
}

private fun column(card: Rect, shares: List<Float>, gap: Float): List<Rect> {
    val total = shares.sum()
    val height = card.height - gap * (shares.size - 1)
    var top = card.top
    return shares.mapIndexed { index, share ->
        val bottom = if (index == shares.lastIndex) card.bottom else top + height * share / total
        Rect(card.left, top, card.right, bottom).also { top = bottom + gap }
    }
}

/**
 * The gap after page [index], which a drag moves: between two pages side by side ([across] is false: it moves
 * sideways), or one above the other ([across] is true: it moves up and down).
 */
data class SplitGap(val index: Int, val rect: Rect, val across: Boolean)

/** The gaps the pages of [rects] can be resized at. A grid's pages keep equal sizes, so it has none. */
fun splitGaps(rects: List<Rect>, layout: SplitLayout): List<SplitGap> = when (layout) {
    SplitLayout.Grid -> emptyList()
    SplitLayout.SideBySide -> rects.zipWithNext().mapIndexed { index, (a, b) -> SplitGap(index, Rect(a.right, a.top, b.left, a.bottom), across = false) }
    SplitLayout.Stacked -> rects.zipWithNext().mapIndexed { index, (a, b) -> SplitGap(index, Rect(a.left, a.bottom, a.right, b.top), across = true) }
}

/**
 * Moves the gap after page [index] by [delta]: that page and the next trade length, and neither gets shorter than
 * [minLength] (unless the two don't have room for that, when they stay equal). [length] is the pages' length
 * without the gaps: their width side by side, their height stacked.
 */
fun dragDivider(sizes: List<Float>, count: Int, index: Int, delta: Float, length: Float, minLength: Float): List<Float> {
    val shares = splitShares(sizes, count).toMutableList()
    val pair = shares[index] + shares[index + 1]
    val min = (minLength / length).coerceAtMost(pair / 2)
    val first = (shares[index] + delta / length).coerceIn(min, pair - min)
    shares[index] = first
    shares[index + 1] = pair - first
    return shares
}

/** Where a dragged tab drops to split with the page: the card's right half (board 7). */
fun splitDropZone(card: Rect, gap: Float) = Rect(card.left + (card.width + gap) / 2, card.top, card.right, card.bottom)
