package app.enso.browser.split

import androidx.compose.ui.geometry.Rect

// Where a split's pages go (board 7). All in window pixels.

/** Each page's share of the width: [sizes] when they fit [count] pages, else equal shares. */
fun splitShares(sizes: List<Float>, count: Int): List<Float> =
    sizes.takeIf { it.size == count && it.all { share -> share > 0f } } ?: List(count) { 1f / count }

/** [count] pages side by side in [card], [gap] apart, sized by [sizes]. */
fun paneRects(card: Rect, count: Int, sizes: List<Float>, gap: Float): List<Rect> {
    if (count <= 1) return listOf(card)
    val shares = splitShares(sizes, count)
    val total = shares.sum()
    val width = card.width - gap * (count - 1)
    var left = card.left
    return shares.mapIndexed { index, share ->
        val right = if (index == count - 1) card.right else left + width * share / total
        Rect(left, card.top, right, card.bottom).also { left = right + gap }
    }
}

/**
 * Moves the divider after page [index] by [delta]: that page and the next trade width, and neither gets narrower
 * than [minWidth] (unless the two don't have room for that, when they stay equal). [width] is the pages' width
 * without the gaps.
 */
fun dragDivider(sizes: List<Float>, count: Int, index: Int, delta: Float, width: Float, minWidth: Float): List<Float> {
    val shares = splitShares(sizes, count).toMutableList()
    val pair = shares[index] + shares[index + 1]
    val min = (minWidth / width).coerceAtMost(pair / 2)
    val left = (shares[index] + delta / width).coerceIn(min, pair - min)
    shares[index] = left
    shares[index + 1] = pair - left
    return shares
}

/** Where a dragged tab drops to split with the page: the card's right half (board 7). */
fun splitDropZone(card: Rect, gap: Float) = Rect(card.left + (card.width + gap) / 2, card.top, card.right, card.bottom)
