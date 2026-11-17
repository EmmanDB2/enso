package app.enso.browser.bottombar

import kotlinx.coroutines.Job
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.animate
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import app.enso.browser.R
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.Tab
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.SpaceIcon
import app.enso.browser.ui.colors
import app.enso.browser.ui.displayTitle
import app.enso.browser.ui.spaceSurface
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing

/**
 * The Quick Tabs view (board B2), in the style of Arc Search: the whole screen, with no sheet.
 *
 * Opening zooms out of the page on screen: its capture ([pageCapture], and where it was) shrinks into the focused
 * card while the space's gradient fades in behind and the neighbouring cards slide in from the sides. Tapping a card
 * zooms back into it, the same animation reversed, with the tab selected underneath so the live page is there when
 * it lands. The cards swipe sideways as one strip, and a card swiped up closes. Older tabs stack behind on the left,
 * their favicons drifting off toward the top left, and the next one peeks in on the right.
 *
 * [tabs] are the space's Quick Tabs in the space's order. The strip is taken once as the view opens, so keeping a tab
 * (Save) doesn't pull its card out from under the zoom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickTabsView(
    tabs: List<Tab>,
    space: Space,
    selectedTabId: String?,
    pageCapture: Pair<ImageBitmap, Rect>?,
    gestureInset: Dp,
    thumbnails: ThumbnailStorage,
    actions: BottomBarActions,
    onCleared: (Int) -> Unit,
) {
    BlockSpaceMenu()
    val scope = rememberCoroutineScope()
    var strip by remember { mutableStateOf(tabs) }
    val start = remember {
        strip.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 }
            ?: strip.indices.maxByOrNull { strip[it].quick?.lastViewedAt ?: 0L } ?: 0
    }
    // Where the strip is, as a card index with fractions; the focused card is the nearest. The finger moves it
    // directly, and a release glides it onto a card.
    var position by remember { mutableFloatStateOf(start.toFloat()) }
    var gliding by remember { mutableStateOf<Job?>(null) }
    // 0 is the page full screen, 1 the cards laid out.
    val zoom = remember { Animatable(0f) }
    // The focused card's lift while it's swiped up to close, in pixels: it counts as settled within half a pixel, not
    // Animatable's default hundredth, whose spring tail held a closed card's gap open for a second or two.
    val lift = remember { Animatable(0f, visibilityThreshold = 0.5f) }
    // After a card closes, the ones after it slide over from where they were ([slideFrom] on, [slide] cards).
    val slide = remember { Animatable(0f) }
    var slideFrom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // Whether the focused card grows from, or shrinks back into, a full-screen page.
    val fromPage = remember { pageCapture != null && strip.getOrNull(start)?.id == selectedTabId }
    var zoomCapture by remember { mutableStateOf(if (fromPage) pageCapture else null) }
    // The tab the capture shows: only its card may draw it.
    val captureTabId = remember { selectedTabId.takeIf { fromPage } }
    var leaving by remember { mutableStateOf(false) }
    val settle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

    LaunchedEffect(Unit) { zoom.animateTo(1f, settle) }

    fun focused() = position.roundToInt().coerceIn(0, (strip.size - 1).coerceAtLeast(0))

    /** Glides the strip to [target], starting at [velocity] (cards a second), on a soft spring that doesn't overshoot. */
    fun glideTo(target: Float, velocity: Float = 0f, then: suspend () -> Unit = {}) {
        gliding?.cancel()
        gliding = scope.launch {
            animate(position, target, velocity, Glide) { value, _ -> position = value }
            then()
        }
    }

    /** Zooms into card [index]: the tab is selected under the view, then the card grows to the page and the view goes. */
    fun zoomInto(index: Int) {
        val tab = strip.getOrNull(index) ?: return actions.showPanel(null)
        if (leaving) return
        leaving = true
        glideTo(index.toFloat()) {
            if (tab.id != selectedTabId || zoomCapture == null) zoomCapture = null
            actions.focus(tab.id)
            zoom.animateTo(0f, settle)
            actions.showPanel(null)
        }
    }

    BackHandler { zoomInto(focused()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val px = { dp: Dp -> with(density) { dp.toPx() } }
        val cardW = width * 0.70f
        val cardH = height * 0.57f
        val cardLeft = (width - cardW) / 2
        val cardTop = height * 0.51f - cardH / 2
        val step = cardW + px(16.dp)
        val pageRect = pageCapture?.second ?: Rect(0f, 0f, width, height)
        val z = zoom.value

        /** Where card [index] shows: its place in the strip, the closing slide, and the stack's shrink behind. */
        fun placeOf(index: Int): Pair<Rect, Float> {
            val d = index - position + if (index >= slideFrom) slide.value else 0f
            val rect = cardRect(d, cardLeft, cardTop, cardW, cardH, step, px(44.dp))
            val shrink = 1f - 0.045f * (-d).coerceIn(0f, 3f)
            return rect.scaledAboutCenter(shrink) to d
        }

        // The space's gradient, fading in as the page shrinks, under every card.
        Box(
            Modifier
                .zIndex(-100f)
                .fillMaxSize()
                .graphicsLayer { alpha = z }
                .spaceSurface(space.colors())
                .pointerInput(strip.size) {
                    // Sideways: the strip. Up on the focused card: close it.
                    var sideways: Boolean? = null
                    var from = 0
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            sideways = null
                            from = focused()
                            tracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            when (sideways) {
                                true -> {
                                    // Carry on at the release's speed and come to rest on the card it reaches.
                                    val cardsPerSecond = -velocity.x / step
                                    var target = exponentialDecay<Float>(frictionMultiplier = FLING_FRICTION)
                                        .calculateTargetValue(position, cardsPerSecond).roundToInt()
                                    // A real flick moves at least one card, however short; a slow drag settles where it's let go.
                                    if (target == from && abs(cardsPerSecond) > FLICK_SPEED) target = from + if (cardsPerSecond > 0) 1 else -1
                                    glideTo(target.coerceIn(0, strip.size - 1).toFloat(), cardsPerSecond)
                                }
                                false -> scope.launch {
                                    val index = focused()
                                    val tab = strip.getOrNull(index)
                                    if (tab != null && (lift.value < -height * 0.22f || velocity.y < -1500f)) {
                                        // A quick fly off the top, then the next card comes over straight away.
                                        lift.animateTo(-height, tween(FLY_OFF_MS, easing = FastOutLinearInEasing))
                                        actions.close(tab.id)
                                        strip = strip.filterNot { it.id == tab.id }
                                        lift.snapTo(0f)
                                        when {
                                            strip.isEmpty() -> actions.showPanel(null)
                                            index < strip.size -> {
                                                // The next card slides over into the gap.
                                                slideFrom = index
                                                slide.snapTo(1f)
                                                slide.animateTo(0f, Glide)
                                                slideFrom = Int.MAX_VALUE
                                            }
                                            // The last one closed: the one before comes forward out of the stack.
                                            else -> glideTo((strip.size - 1).toFloat())
                                        }
                                    } else {
                                        lift.animateTo(0f, settle)
                                    }
                                }
                                null -> {}
                            }
                        },
                    ) { change, drag ->
                        if (leaving) return@detectDragGestures
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (sideways == null) sideways = abs(drag.x) > abs(drag.y)
                        if (sideways == true) {
                            gliding?.cancel()
                            // Past either end it gives a little, then stops.
                            position = (position - drag.x / step).coerceIn(-0.3f, strip.size - 0.7f)
                        } else {
                            scope.launch { lift.snapTo((lift.value + drag.y).coerceAtMost(0f)) }
                        }
                    }
                }
                .pointerInput(strip.size) {
                    detectTapGestures { tap ->
                        // The topmost card under the tap: the focused one, then the others nearest it.
                        val hit = strip.indices.sortedBy { abs(it - position) }.firstOrNull { placeOf(it).first.contains(tap) }
                        if (hit != null) zoomInto(hit)
                    }
                },
        )

        strip.forEachIndexed { index, tab ->
            var (rect, d) = placeOf(index)
            val focusedCard = index == focused()
            val growing = focusedCard && (leaving || (zoomCapture != null && tab.id == captureTabId))
            // The neighbours slide in from the sides as the page shrinks.
            if (!focusedCard) rect = rect.translate((1 - z) * width * 0.6f * (if (d < 0) -1 else 1), 0f)
            if (growing) {
                rect = Rect(
                    lerp(pageRect.left, rect.left, z),
                    lerp(pageRect.top, rect.top, z),
                    lerp(pageRect.right, rect.right, z),
                    lerp(pageRect.bottom, rect.bottom, z),
                )
            }
            if (focusedCard) rect = rect.translate(0f, lift.value)
            val behind = (-d).coerceAtLeast(0f)
            val corner = with(density) { (CardCorner.toPx() * if (growing) z else 1f).toDp() }
            val shape = RoundedCornerShape(corner)
            val alpha = when {
                growing -> 1f
                behind > 0f -> (4f - behind).coerceIn(0f, 1f) * z
                else -> z
            }
            Box(
                Modifier
                    // Newer cards lie on older ones, so a card coming in slides over the one going back into the stack.
                    .zIndex(if (growing) 1000f else index.toFloat())
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                    .graphicsLayer { this.alpha = alpha }
                    .shadow(if (growing && z < 0.5f) 0.dp else 14.dp, shape)
                    .background(Color.White, shape)
                    .clip(shape),
            ) {
                val capture = zoomCapture?.first
                if (capture != null && tab.id == captureTabId) {
                    Image(capture, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    TabThumbnail(tab.id, thumbnails, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Favicon(tab, size = 40.dp) }
                    }
                }
            }
            // Each card's favicon rides just above its own top-left corner (a touch higher the further back it sits,
            // so the stack's step up and away). The focused card's title sits beside it.
            val titleFade = if (focusedCard) (1f - 2f * abs(position - index)).coerceIn(0f, 1f) else 0f
            Row(
                Modifier
                    .zIndex(if (growing) 1000f else index.toFloat())
                    .offset { IntOffset(rect.left.roundToInt(), (rect.top - px(32.dp) - behind * px(4.dp)).roundToInt()) }
                    .width(with(density) { cardW.toDp() })
                    // The stack's favicons fade as they drift, so only the nearest two or so show.
                    .graphicsLayer { this.alpha = if (growing) z else alpha * (1f - 0.4f * behind).coerceAtLeast(0f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(tab, size = 22.dp)
                if (titleFade > 0f) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tab.displayTitle(),
                        style = EnsoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { this.alpha = titleFade },
                    )
                }
            }
        }

        // The bar's row, dissolved into floating buttons: Clear All, Save, and the chevron to the actions sheet.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = gestureInset + 12.dp)
                .offset { IntOffset(0, ((1 - z) * px(40.dp)).roundToInt()) }
                .graphicsLayer { alpha = z },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(PillFill)
                    .clickable { onCleared(actions.clearQuickTabs()) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_archive), contentDescription = null, Modifier.size(18.dp), tint = EnsoColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bar_clear_all), style = EnsoType.Row.copy(fontSize = 15.sp))
            }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(EnsoColors.CurrentTab)
                    .combinedClickable(
                        onClick = {
                            strip.getOrNull(focused())?.let { tab ->
                                actions.keep(tab.id)
                                zoomInto(focused())
                            }
                        },
                        onLongClick = { strip.getOrNull(focused())?.let { actions.keepElsewhere(it.id) } },
                    ),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpaceIcon(space.icon, size = 22.dp, tint = Color(space.accent), fallbackName = space.name)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.bar_save), style = EnsoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PillFill)
                    .clickable {
                        strip.getOrNull(focused())?.let { actions.focus(it.id) }
                        actions.showPanel(BarPanel.Actions)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_up), contentDescription = stringResource(R.string.bar_actions), Modifier.size(24.dp), tint = EnsoColors.TextPrimary)
            }
        }
    }
}

/** The cards' corners. */
private val CardCorner = 10.dp

/** How quickly a fling slows (Compose's decay ×): high, so a fling carries one card, and only a fast one two. */
private const val FLING_FRICTION = 2.5f

/** Cards a second a release needs to move on a card by itself. */
private const val FLICK_SPEED = 1.3f

/** The strip's glide onto a card: soft, and no overshoot even when a fling starts it fast. */
private val Glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 150f)

private fun Rect.scaledAboutCenter(scale: Float): Rect {
    val dx = width * (1 - scale) / 2
    val dy = height * (1 - scale) / 2
    return Rect(left + dx, top + dy, right - dx, bottom - dy)
}

/**
 * Where a card sits [d] cards from the focused one: those after it in a row to the right, those before it stacked
 * behind on the left, each further one tucked less far ([stackStep] for the first, then half as much again each).
 */
private fun cardRect(d: Float, left: Float, top: Float, width: Float, height: Float, step: Float, stackStep: Float): Rect {
    val x = if (d >= 0f) {
        left + d * step
    } else {
        // 1 − 0.5^t over 1 − 0.5: 1 card back is a full step, then half, a quarter…
        left - stackStep * (1f - 0.5f.pow(-d)) / 0.5f
    }
    return Rect(x, top, x + width, top + height)
}

/** How long a card swiped up to close takes to leave. */
private const val FLY_OFF_MS = 160
