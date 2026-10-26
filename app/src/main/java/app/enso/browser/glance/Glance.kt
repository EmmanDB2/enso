package app.enso.browser.glance

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.spacemenu.BlurredPage
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import kotlin.math.roundToInt

/** A link being previewed (board 8): its custom tab, which stays out of the user's tabs, and the tab it was held on. */
data class GlanceState(val tabId: String, val url: String, val parentTabId: String?)

/**
 * Where Glance goes, in window pixels: the [area] it dims (the page card unfolded, the whole screen on the cover),
 * the [card], its [page] under the domain header, and its buttons: a column beside the card unfolded (board 8),
 * a row below it on the cover.
 */
data class GlanceLayout(val area: Rect, val card: Rect, val page: Rect, val buttons: Offset, val buttonsInRow: Boolean)

/**
 * Lays Glance out over [area], keeping the card and buttons inside [room] (clear of the status bar and gesture
 * handle on the cover): unfolded, the card and its buttons centered as a group (board 8); on the cover, the card
 * filling most of the screen with the buttons in a row below it, within reach of the thumb.
 */
fun glanceLayout(area: Rect, room: Rect, inner: Boolean, density: Float): GlanceLayout {
    fun px(dp: Dp) = dp.value * density
    val button = px(GlanceButton)
    val gap = px(ButtonGap)
    return if (inner) {
        val margin = px(40.dp)
        val width = minOf(room.width * 0.72f, room.width - 2 * px(24.dp) - gap - button)
        val left = room.left + (room.width - (width + gap + button)) / 2
        val card = Rect(left, room.top + margin, left + width, room.bottom - margin)
        GlanceLayout(area, card, card.copy(top = card.top + px(Header)), Offset(card.right + gap, card.top), buttonsInRow = false)
    } else {
        val margin = px(16.dp)
        val card = Rect(room.left + margin, room.top + margin, room.right - margin, room.bottom - margin - button - gap)
        GlanceLayout(area, card, card.copy(top = card.top + px(Header)), Offset(card.center.x, card.bottom + gap), buttonsInRow = true)
    }
}

interface GlanceActions {
    fun close()

    /** The preview becomes a tab below the one it was opened from, and shows. */
    fun openAsTab()

    /** The preview becomes a tab, split to the right of the page (unfolded only). */
    fun split()
}

/**
 * Glance (board 8): the held link's page in a floating card over the blurred, dimmed page, with Close, Open as tab
 * and (unfolded) Split beside it. A tap around the card, or Back, closes it.
 *
 * The card's page is a GeckoView under this overlay, which ignores view clipping; this draws everything around it
 * and clears a hole with the card's rounded corners for it to show through. The pages behind are hidden meanwhile
 * (their blurred captures stand in for them), so the preview is the only page under the hole.
 */
@Composable
fun GlanceOverlay(
    glance: GlanceState,
    layout: GlanceLayout,
    backdrop: List<Pair<ImageBitmap, Rect>>,
    areaCorner: Dp,
    canSplit: Boolean,
    actions: GlanceActions,
) {
    BackHandler(onBack = actions::close)
    BlockSpaceMenu()
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val corner = Corner.toPx()
                val hole = Path().apply {
                    addRoundRect(RoundRect(layout.page, bottomLeft = CornerRadius(corner), bottomRight = CornerRadius(corner)))
                }
                drawPath(hole, Color.Black, blendMode = BlendMode.Clear)
            },
    ) {
        Box(Modifier.placed(layout.area, density).clip(RoundedCornerShape(areaCorner))) {
            backdrop.forEach { (image, bounds) ->
                Box(Modifier.offset { IntOffset((-layout.area.left).roundToInt(), (-layout.area.top).roundToInt()) }) {
                    BlurredPage(image, bounds)
                }
            }
            Box(Modifier.fillMaxSize().background(Dim))
        }
        Column(
            Modifier
                .placed(layout.card, density)
                .shadow(24.dp, RoundedCornerShape(Corner))
                .background(EnsoColors.CurrentTab, RoundedCornerShape(Corner)),
        ) {
            Row(Modifier.fillMaxWidth().height(Header).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AddressInput.displayHost(glance.url),
                    style = EnsoType.Row.copy(fontSize = 18.sp, color = EnsoColors.TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(EnsoColors.TextSecondary.copy(alpha = 0.12f)))
        }
    }
    // Taps around the card close it. The card's page takes its own touches: nothing here covers it, so they pass
    // through the overlay to the preview.
    TapsAround(layout.card, density, onTap = actions::close)
    Box(Modifier.placed(Rect(layout.card.topLeft, layout.page.topRight), density).pointerInput(Unit) { detectTapGestures { } })
    GlanceButtons(layout, canSplit, actions)
}

/** Boxes over everything but [card], closing Glance on a tap. */
@Composable
private fun TapsAround(card: Rect, density: Density, onTap: () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val screen = Rect(0f, 0f, window.width.toFloat(), window.height.toFloat())
    listOf(
        Rect(screen.left, screen.top, screen.right, card.top),
        Rect(screen.left, card.bottom, screen.right, screen.bottom),
        Rect(screen.left, card.top, card.left, card.bottom),
        Rect(card.right, card.top, screen.right, card.bottom),
    ).filter { it.width > 0f && it.height > 0f }.forEach { strip ->
        Box(Modifier.placed(strip, density).pointerInput(Unit) { detectTapGestures { onTap() } })
    }
}

@Composable
private fun GlanceButtons(layout: GlanceLayout, canSplit: Boolean, actions: GlanceActions) {
    val density = LocalDensity.current
    val buttons = @Composable {
        RoundButton(R.drawable.ic_close, R.string.glance_close, actions::close)
        RoundButton(R.drawable.ic_open_tab, R.string.glance_open_as_tab, actions::openAsTab)
        if (canSplit) RoundButton(R.drawable.ic_split, R.string.glance_split, actions::split)
    }
    val spacing = Arrangement.spacedBy(18.dp)
    if (layout.buttonsInRow) {
        // Centered under the card.
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, layout.buttons.y.roundToInt()) },
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        ) { buttons() }
    } else {
        Column(Modifier.offset { IntOffset(layout.buttons.x.roundToInt(), layout.buttons.y.roundToInt()) }, verticalArrangement = spacing) {
            buttons()
        }
    }
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(GlanceButton)
            .shadow(8.dp, CircleShape)
            .background(EnsoColors.CurrentTab, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label), Modifier.size(26.dp), tint = EnsoColors.TextPrimary)
    }
}

/** Places this box at [rect] in window pixels, in an overlay that fills the window. */
private fun Modifier.placed(rect: Rect, density: Density): Modifier = this
    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })

private val Corner = 24.dp
private val Header = 48.dp
private val GlanceButton = 56.dp
private val ButtonGap = 24.dp

/** Over the blurred page: dimmed, as on board 8. */
private val Dim = Color.Black.copy(alpha = 0.3f)
