package app.enso.browser.spacemenu

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.SpaceIcon
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One space in the wave. [tint] is its Zen theme gradient, top to bottom, or just its accent when the theme
 * has no colors.
 */
data class WaveSpace(val id: String, val name: String, val icon: String, val tint: List<Color>)

/** What the wave is doing. */
sealed interface WaveMode {
    /** Opened by a hold: the wave follows the thumb, and letting go switches to the space under it. */
    data object Menu : WaveMode

    /** Picking a space for a Quick Tab ("Add to another space"): touch or drag along the list and lift on one. */
    data class Picker(val tabId: String) : WaveMode
}

/**
 * The wave at one moment (spec: board 3): the rows' line, as a taut string held at the screen's [top] and
 * [bottom] and pushed out by the thumb. Rows sit still on it, [pitch] apart along the [edge] from [origin]; the
 * wave is centred on [position], a row index with fractions.
 *
 * The push has two parts: a bump around the thumb with a smooth falloff, so it glides between rows, and the
 * string's lean ([tension]), which runs in near-straight lines from both ends of the screen to the thumb.
 */
class WaveShape(
    val count: Int,
    val position: Float,
    private val origin: Float,
    private val pitch: Float,
    private val edge: Float,
    /** The wave sits on the left edge (left-handed mode). */
    val left: Boolean,
    private val inset: Float,
    /** How far the bump around the thumb pushes out, on top of the string's lean. */
    private val bump: Float,
    /** How far the string leans out at the thumb. */
    private val tension: Float,
    val top: Float,
    val bottom: Float,
    /** How rounded the string's lean is where the thumb pushes it, so it has no point. */
    private val tip: Float,
) {
    /** Screen height of row [u], counted in rows. */
    fun y(u: Float): Float = origin + pitch * u

    /** How far row [u] sits from the edge. */
    private fun outward(u: Float): Float = inset + bump * falloff(u - position) + tension * lean(y(u))

    /**
     * The string's lean at screen height [y]: 1 at the wave's centre, falling in near-straight lines to 0 at the
     * screen's top and bottom, and rounded over [tip] at the peak.
     */
    private fun lean(y: Float): Float {
        val peak = y(position)
        val reach = if (y < peak) peak - top else bottom - peak
        if (reach <= 0f) return 0f
        val d = abs(y - peak)
        val soft = sqrt(d * d + tip * tip) - tip
        val softReach = sqrt(reach * reach + tip * tip) - tip
        return (1f - soft / softReach).coerceIn(0f, 1f)
    }

    fun x(u: Float): Float = if (left) edge + outward(u) else edge - outward(u)

    fun point(u: Float) = Offset(x(u), y(u))

    fun center(index: Int) = point(index.toFloat())
}

/**
 * The share of the bulge a row [d] rows from the wave's centre gets: 1, a half one row away and about 0.16 two
 * away (board 3's falloff), then next to nothing. Rounded at the peak, so the wave has no point.
 */
internal fun falloff(d: Float): Float = (1f + FALLOFF_WIDTH * d * d).pow(-FALLOFF_POWER)

/** Names of the spaces around the selected one by distance from the wave's centre: 16 sp, then 13 and 12. */
private fun nameSize(d: Float): Float {
    val a = abs(d)
    val i = a.toInt()
    if (i >= NameSizes.size - 1) return NameSizes.last()
    return NameSizes[i] + (NameSizes[i + 1] - NameSizes[i]) * (a - i)
}

private val NameSizes = floatArrayOf(16f, 16f, 13f, 12f)

@Stable
class SpaceWaveState {
    var mode by mutableStateOf<WaveMode?>(null)
        private set
    var spaces by mutableStateOf(emptyList<WaveSpace>())
        private set

    /** The wave's centre, as a row index with fractions. */
    var position by mutableFloatStateOf(0f)
        private set

    /** Screen height of the first row. It only moves when the list has to scroll to reach every space. */
    private var origin by mutableFloatStateOf(0f)

    /** Where the finger is, while the menu follows one. */
    var finger by mutableStateOf<Offset?>(null)
        private set

    /** The finger is far from the list, so letting go does nothing. */
    private var slidOff by mutableStateOf(false)

    /** The tint while no space is hovered: the current space's. */
    var restingTint by mutableStateOf(emptyList<Color>())
        private set

    /** The space at the wave's centre, or null when it's past either end of the list or the finger slid off. */
    var hovered by mutableStateOf<Int?>(null)
        private set

    /** The page behind, captured as the wave opened, and where it sat on screen. Null until the capture arrives. */
    var backdrop by mutableStateOf<ImageBitmap?>(null)
        private set
    var backdropBounds by mutableStateOf(Rect.Zero)
        private set

    /** Counts openings, so a page capture that arrives after its menu closed is dropped. */
    var openings = 0
        private set

    private var left = false
    private var bounds = Rect.Zero

    /** The whole window's height: the string is held at its top and bottom. */
    private var screenHeight = 0f
    private var density = Density(1f)
    private var pitch = 1f
    private var start = Offset.Zero
    private var startIndex = 0
    private var travelUp = 1f
    private var travelDown = 1f

    /**
     * Opens the menu for a hold at [touch], with the current space under the thumb. [bounds] is the screen minus
     * the system bars.
     */
    fun openMenu(
        spaces: List<WaveSpace>,
        currentIndex: Int,
        touch: Offset,
        left: Boolean,
        bounds: Rect,
        screenHeight: Float,
        density: Density,
    ) {
        setUp(spaces, spaces[currentIndex].tint, left, bounds, screenHeight, density)
        with(density) {
            pitch = Pitch.toPx()
            // A row per pitch of travel, so the rows stay still and the wave stays under the thumb. When there
            // isn't room to reach every space (and just past the ends, to cancel), a row takes less travel and
            // the list scrolls along with the wave.
            val minTravel = MinTravel.toPx()
            travelUp = ((touch.y - bounds.top - EndMargin.toPx()) / (currentIndex + END_SLACK)).coerceIn(minTravel, pitch)
            travelDown = ((bounds.bottom - touch.y) / (spaces.size - 1 - currentIndex + END_SLACK)).coerceIn(minTravel, pitch)
        }
        start = touch
        startIndex = currentIndex
        finger = touch
        slidOff = false
        hovered = currentIndex
        follow(touch.y, currentIndex.toFloat())
        mode = WaveMode.Menu
    }

    /** Follows the finger. True when the hovered space changed, for a haptic tick. */
    fun move(to: Offset): Boolean {
        val before = hovered
        finger = to
        val dy = to.y - start.y
        slidOff = abs(to.x - start.x) > with(density) { SidewaysCancel.toPx() }
        follow(to.y, startIndex + dy / if (dy < 0) travelUp else travelDown)
        return hovered != before
    }

    /** Closes the menu. Returns the space to switch to, unless the finger slid off or stayed on the current one. */
    fun release(): String? {
        val target = hovered?.takeIf { it != startIndex }?.let { spaces[it].id }
        close()
        return target
    }

    /** Opens the wave as a picker of [spaces] for a Quick Tab, resting a little below the middle of the screen. */
    fun openPicker(
        tabId: String,
        spaces: List<WaveSpace>,
        restingTint: List<Color>,
        left: Boolean,
        bounds: Rect,
        screenHeight: Float,
        density: Density,
    ) {
        setUp(spaces, restingTint, left, bounds, screenHeight, density)
        with(density) {
            val top = bounds.top + EndMargin.toPx()
            val bottom = bounds.bottom - EndMargin.toPx()
            pitch = minOf(Pitch.toPx(), (bottom - top) / maxOf(spaces.size - 1, 1))
            val length = pitch * (spaces.size - 1)
            origin = (bounds.top + bounds.height * PICKER_MIDDLE - length / 2).coerceIn(top, maxOf(top, bottom - length))
        }
        position = (spaces.size - 1) / 2f
        finger = null
        // Nothing is picked until the finger is on the list.
        slidOff = true
        hovered = null
        mode = WaveMode.Picker(tabId)
    }

    /** The picker follows the finger: the row level with it is hovered, while the finger is near the list. */
    fun pickAt(at: Offset) {
        val fromEdge = if (left) at.x - bounds.left else bounds.right - at.x
        slidOff = fromEdge > with(density) { PickerReach.toPx() }
        position = ((at.y - origin) / pitch).coerceIn(-1f - END_SLACK, spaces.size + END_SLACK)
        updateHovered()
    }

    /** Closes the picker. Returns the space the finger lifted on, if any. */
    fun pickOnLift(): String? {
        val target = hovered?.let { spaces[it].id }
        close()
        return target
    }

    /** Shows the page captured for opening number [opening] behind the frost, unless that menu has since closed. */
    fun showBackdrop(opening: Int, image: ImageBitmap, bounds: Rect) {
        if (opening != openings || mode == null) return
        backdrop = image
        backdropBounds = bounds
    }

    fun close() {
        mode = null
        finger = null
        backdrop = null
    }

    /** The wave's shape with its centre drawn at [drawnPosition] (eased toward [position]), its push scaled by [amount]. */
    fun shape(amount: Float, drawnPosition: Float = position): WaveShape = with(density) {
        val push = Bulge.toPx() * amount
        WaveShape(
            count = spaces.size,
            position = drawnPosition,
            origin = origin,
            pitch = pitch,
            edge = if (left) bounds.left else bounds.right,
            left = left,
            inset = Inset.toPx(),
            bump = push * (1f - TENSION_SHARE),
            tension = push * TENSION_SHARE,
            top = 0f,
            bottom = screenHeight,
            tip = LeanTip.toPx(),
        )
    }

    /** How much the wave bulges: fully on a space, easing back past either end of the list or once slid off. */
    fun engagement(): Float {
        if (slidOff) return RESTING_BULGE
        val past = maxOf(-0.5f - position, position - (spaces.size - 0.5f), 0f)
        return (1f - past).coerceIn(RESTING_BULGE, 1f)
    }

    /** Puts the wave's centre at [toPosition], level with the finger at [fingerY]. */
    private fun follow(fingerY: Float, toPosition: Float) {
        position = toPosition.coerceIn(-1f - END_SLACK, spaces.size + END_SLACK)
        origin = fingerY - pitch * position
        updateHovered()
    }

    /**
     * The row nearest the wave's centre, held a little past the halfway point so a thumb resting between two rows
     * doesn't flutter between them.
     */
    private fun updateHovered() {
        val current = hovered
        val nearest = position.roundToInt()
        hovered = when {
            slidOff -> null
            current != null && current in spaces.indices && abs(position - current) <= 0.5f + HOVER_HOLD -> current
            else -> nearest.takeIf { it in spaces.indices }
        }
    }

    private fun setUp(
        spaces: List<WaveSpace>,
        restingTint: List<Color>,
        left: Boolean,
        bounds: Rect,
        screenHeight: Float,
        density: Density,
    ) {
        this.spaces = spaces
        this.restingTint = restingTint
        this.left = left
        this.bounds = bounds
        this.screenHeight = screenHeight
        this.density = density
        openings++
        backdrop = null
    }
}

/**
 * The space menu (board 3): the page blurs under a light frost and takes the hovered space's tint, and the
 * spaces sit in a list along the edge that bulges out in a wave around the thumb, with an ink stroke along it.
 * The hovered space sits in a frosted pill. As a picker, the wave follows a touch on the list, and lifting picks
 * a space.
 */
@Composable
fun SpaceWave(state: SpaceWaveState, onPick: (tabId: String, spaceId: String) -> Unit) {
    val mode = state.mode ?: return
    val hovered = state.hovered
    val tint = hovered?.let { state.spaces.getOrNull(it)?.tint } ?: state.restingTint
    val engaged by animateFloatAsState(state.engagement(), tween(ENGAGE_MS), label = "engaged")
    // The bulge eases after the thumb on a soft, critically damped spring: no snapping, no overshoot.
    val drawn by animateFloatAsState(state.position, spring(dampingRatio = 1f, stiffness = WAVE_STIFFNESS), label = "wave")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MS, easing = FastOutSlowInEasing)) }
    val picker = mode as? WaveMode.Picker
    if (picker != null) BackHandler { state.close() }
    val haptics = LocalHapticFeedback.current
    val shape = state.shape(engaged * appear.value, drawn)

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (picker == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(picker) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            state.pickAt(down.position)
                            var ticked = state.hovered
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                state.pickAt(change.position)
                                change.consume()
                                if (state.hovered != ticked) {
                                    ticked = state.hovered
                                    if (ticked != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                            state.pickOnLift()?.let { onPick(picker.tabId, it) }
                        }
                    }
                },
            ),
    ) {
        BlurredPage(state.backdrop, state.backdropBounds)
        Box(Modifier.fillMaxSize().background(EnsoColors.WaveFrost))
        Crossfade(targetState = tint, animationSpec = tween(TINT_MS), label = "tint") { colors ->
            Box(Modifier.fillMaxSize().background(tintBrush(colors), alpha = TINT_ALPHA))
        }
        Canvas(Modifier.fillMaxSize()) { drawInk(shape) }
        WaveRows(state.spaces, shape, hovered, labelAlpha = appear.value)
    }
}

/** The page captured as the wave opened, blurred, fading in once the capture arrives. Blur needs Android 12. */
@Composable
internal fun BlurredPage(image: ImageBitmap?, bounds: Rect) {
    if (image == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val density = LocalDensity.current
    val shown = remember(image) { Animatable(0f) }
    LaunchedEffect(image) { shown.animateTo(1f, tween(BACKDROP_MS)) }
    Image(
        image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .graphicsLayer { alpha = shown.value }
            .blur(PageBlur, BlurredEdgeTreatment.Rectangle),
    )
}

/** A space's tint, top to bottom: its gradient, or its one color. */
private fun tintBrush(colors: List<Color>): Brush = when {
    colors.size >= 2 -> Brush.verticalGradient(colors)
    colors.size == 1 -> SolidColor(colors.single())
    else -> SolidColor(Color.Transparent)
}

/**
 * The ink brush stroke along the wave: [InkWidth] wide at the thumb, tapering to nothing about two rows away,
 * with a slight hand-drawn wobble. The wobble is fixed to the rows, so the stroke slides along it like ink.
 */
private fun DrawScope.drawInk(shape: WaveShape) {
    val half = InkWidth.toPx() / 2
    val sides = ArrayList<Offset>()
    val others = ArrayList<Offset>()
    var u = shape.position - INK_REACH
    while (u <= shape.position + INK_REACH) {
        val tangent = shape.point(u + INK_STEP / 2) - shape.point(u - INK_STEP / 2)
        val length = tangent.getDistance()
        val normal = if (length > 0f) Offset(-tangent.y / length, tangent.x / length) else Offset(1f, 0f)
        val d = u - shape.position
        val wobble = 1f + 0.08f * sin(u * 5.3f) + 0.05f * sin(u * 11.7f + 1f)
        val width = half * exp(-d * d / (2 * INK_SIGMA * INK_SIGMA)) * wobble
        val at = shape.point(u)
        sides += at + normal * width
        others += at - normal * width
        u += INK_STEP
    }
    if (sides.isEmpty()) return
    val path = Path().apply {
        moveTo(sides[0].x, sides[0].y)
        for (point in sides.drop(1) + others.asReversed()) lineTo(point.x, point.y)
        close()
    }
    drawPath(path, EnsoColors.WaveInk)
}

/** Each space's icon on the wave, with its name on the side away from the edge. */
@Composable
private fun WaveRows(spaces: List<WaveSpace>, shape: WaveShape, hovered: Int?, labelAlpha: Float) {
    Layout(
        content = {
            spaces.forEachIndexed { index, space ->
                WaveRow(space, distance = index - shape.position, hovered = index == hovered, iconFirst = shape.left, alpha = labelAlpha)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        // Rows are placed by their icon's centre, which sits [RingInset] in from the row's edge-side end.
        val iconEnd = IconBox.toPx() / 2 + RingInset.toPx()
        val margin = Margin.toPx()
        val placeables = measurables.mapIndexed { index, measurable ->
            val x = shape.x(index.toFloat())
            val room = if (shape.left) constraints.maxWidth - margin - (x - iconEnd) else x + iconEnd - margin
            measurable.measure(Constraints(maxWidth = room.roundToInt().coerceAtLeast(IconBox.roundToPx())))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val center = shape.center(index)
                val x = if (shape.left) center.x - iconEnd else center.x + iconEnd - placeable.width
                placeable.place(x.roundToInt(), (center.y - placeable.height / 2f).roundToInt())
            }
        }
    }
}

/**
 * One space: dark gray text that lightens and shrinks with distance from the thumb, or, when hovered, a
 * frosted pill with a larger name and icon. The two cross-fade.
 */
@Composable
private fun WaveRow(space: WaveSpace, distance: Float, hovered: Boolean, iconFirst: Boolean, alpha: Float) {
    val chosen by animateFloatAsState(if (hovered) 1f else 0f, tween(TINT_MS), label = "chosen")
    val fade = lerp(NEAR_TEXT_ALPHA, FAR_TEXT_ALPHA, ((abs(distance) - 1f) / 2f).coerceIn(0f, 1f))
    val ink = EnsoColors.WaveText.copy(alpha = lerp(fade, 1f, chosen))
    val pillShape = RoundedCornerShape(percent = 50)
    Box(Modifier.graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { this.alpha = chosen }
                .shadow(PillShadow, pillShape, ambientColor = PillShadowColor, spotColor = PillShadowColor)
                .background(EnsoColors.WavePill, pillShape),
        )
        Row(
            Modifier.padding(start = if (iconFirst) RingInset else PillPadding, end = if (iconFirst) PillPadding else RingInset),
            horizontalArrangement = Arrangement.spacedBy(NameGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = @Composable {
                Text(
                    space.name,
                    style = EnsoType.Row.copy(
                        fontSize = lerp(nameSize(distance), SELECTED_NAME_SP, chosen).sp,
                        fontWeight = if (chosen > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                        color = ink,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (!iconFirst) name()
            Box(Modifier.size(IconBox), contentAlignment = Alignment.Center) {
                SpaceIcon(space.icon, size = lerp(18f, 22f, chosen).dp, tint = ink, fallbackName = space.name)
            }
            if (iconFirst) name()
        }
    }
}

// Board 3's sizes on the cover screen.
private val Pitch = 72.dp
private val Inset = 30.dp
private val IconBox = 44.dp
private val NameGap = 10.dp
private val Margin = 12.dp

/** How far the string is pushed out at the thumb: the bump plus the string's lean. */
private val Bulge = 50.dp

/** How rounded the string is where the thumb pushes it. */
private val LeanTip = 60.dp

/** The pill's padding on the name's side, and between the ring and the pill's edge on the icon's side. */
private val PillPadding = 18.dp
private val RingInset = 6.dp
private val PillShadow = 10.dp
private val PillShadowColor = Color.Black.copy(alpha = 0.35f)

/** The ink stroke's width at the thumb. */
private val InkWidth = 16.dp

private val PageBlur = 20.dp

/** Rows stay this far inside the top and bottom of the screen in the picker, and when reaching every space. */
private val EndMargin = 36.dp

/** The least thumb travel per space, when the list has to scroll to fit. */
private val MinTravel = 22.dp

/** How far the finger can drift sideways before the menu counts it as slid off. */
private val SidewaysCancel = 160.dp

/** How far from the edge a touch still counts as on the picker's list. */
private val PickerReach = 240.dp

/** [falloff]'s shape: 0.5 at one row and about 0.16 at two, with a rounded peak. */
private const val FALLOFF_WIDTH = 0.528f
private const val FALLOFF_POWER = 1.635f

/** The share of the push that comes from the string's lean rather than the bump around the thumb. */
private const val TENSION_SHARE = 0.22f

/** How far past either end the finger can go, in rows, while it still counts as on the list's end. */
private const val END_SLACK = 0.8f

/** How far past halfway, in rows, the hovered space holds on before the next one takes over. */
private const val HOVER_HOLD = 0.12f

/** How much the wave still bulges while nothing is hovered. */
private const val RESTING_BULGE = 0.3f

/** The ink stroke's Gaussian taper, in rows, and how far either side of the thumb it's drawn. */
private const val INK_SIGMA = 0.8f
private const val INK_REACH = 2.2f
private const val INK_STEP = 0.04f

/** Name sizes: the selected space's, in its pill. The others come from [nameSize]. */
private const val SELECTED_NAME_SP = 22f

/** Text opacity for the spaces next to the selected one, and for those three or more rows away. */
private const val NEAR_TEXT_ALPHA = 0.85f
private const val FAR_TEXT_ALPHA = 0.45f

/** The picker's list rests centred this far down the screen, within thumb reach. */
private const val PICKER_MIDDLE = 0.58f

/** The spring the drawn bulge follows the thumb with: critically damped and soft. */
private const val WAVE_STIFFNESS = 400f

private const val TINT_ALPHA = 0.24f
private const val TINT_MS = 150
private const val ENGAGE_MS = 140
private const val APPEAR_MS = 200
private const val BACKDROP_MS = 120
