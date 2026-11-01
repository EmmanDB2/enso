package app.enso.browser.start

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.enso.browser.ui.EnsoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// The ensō fidget on the blank start page (docs/mockups F1–F4): the brand mark, generated so it can move. It spins
// when dragged, and its head sweeps round into a full circle when held. Everything below works in the mark's 512×512 grid, centered on
// (256, 256); docs/brand/enso-mark.svg shows the box from 50 to 462 of it.

/**
 * The ensō, centered on the blank page, about 240 dp: faint at rest, darker while touched. Only touches that start on
 * its circle are its own; the rest go to the start screen behind it. [glow] is the current space's colors, its accent
 * (Zen's primary) first.
 */
@Composable
fun EnsoFidget(glow: List<Color>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val haptics = remember { FidgetHaptics(context, view) }

    val rotation = remember { Animatable(0f) }
    val headSweep = remember { Animatable(0f) }
    // The whole ensō's size, which bounces when a closed loop lets go.
    val bounce = remember { Animatable(1f) }
    var touched by remember { mutableStateOf(false) }
    // How fast it turns under the finger, degrees a second; a fling's speed is the animation's own.
    var dragSpeed by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // The way it last turned (1 clockwise, -1 not), which it carries on in to get home.
    var spinDirection by remember { mutableFloatStateOf(1f) }

    val animationsOn = remember { ValueAnimator.areAnimatorsEnabled() }
    val breath = if (animationsOn) {
        rememberInfiniteTransition(label = "ensoBreath")
            .animateFloat(-1f, 1f, infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
            .value
    } else {
        0f
    }
    val inkAlpha by animateFloatAsState(if (touched) TOUCHED_ALPHA else IDLE_ALPHA, tween(INK_FADE_MS), label = "ink")

    // A tick for every 30° it turns, by hand or on its own, never closer together than the motor can make out.
    LaunchedEffect(Unit) {
        var step = floor(rotation.value / TICK_DEGREES)
        var lastTick = 0L
        snapshotFlow { rotation.value }.collect { degrees ->
            val now = floor(degrees / TICK_DEGREES)
            if (now != step) {
                step = now
                val time = SystemClock.uptimeMillis()
                if (time - lastTick >= TICK_GAP_MS) {
                    lastTick = time
                    haptics.tick(SPIN_TICK_SCALE)
                }
            }
        }
    }

    // The loop has closed and lets go by itself: a bounce, the head unwinds to the open ensō, and the unwinding sends it
    // spinning on, about a turn, to settle at rest.
    // While the finger is turning it, the turning stays the finger's ([spin] false).
    suspend fun letGo(spin: Boolean) = coroutineScope {
        launch { bounce.animateTo(1f, spring(dampingRatio = BOUNCE_DAMPING, stiffness = BOUNCE_STIFFNESS), initialVelocity = BOUNCE_VELOCITY) }
        launch { headSweep.animateTo(0f, tween(UNWIND_MS, easing = FastOutSlowInEasing)) }
        if (spin) {
            spinDirection = 1f
            rotation.spinHome(unwindKick(rotation.value), 1f)
        }
    }

    val stroke = remember { StrokeCache() }
    Canvas(
        modifier
            .size(FidgetSize)
            .pointerInput(Unit) {
                val unit = size.width / MARK_BOX
                fun toGrid(position: Offset) = Offset(MARK_ORIGIN + position.x / unit, MARK_ORIGIN + position.y / unit)
                fun angleOf(position: Offset): Float {
                    val grid = toGrid(position)
                    return Math.toDegrees(atan2((grid.y - CENTER).toDouble(), (grid.x - CENTER).toDouble())).toFloat()
                }
                val slop = HOLD_SLOP.toPx()
                val holdSlop = HOLD_BREAK_SLOP.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val grid = toGrid(down.position)
                    // Only a touch that starts on the circle is the ensō's; the hole and around it are the page's.
                    if (hypot(grid.x - CENTER, grid.y - CENTER) !in TOUCH_BAND) return@awaitEachGesture
                    down.consume()
                    touched = true
                    // A touch catches it mid-spin.
                    scope.launch { rotation.stop() }
                    var turned = rotation.value
                    // The hold has started (and may since have closed the loop, which then lets go by itself).
                    var holding = false
                    var closed = false
                    // The finger is turning it. A drag spins it whenever it comes: before a hold (which then never
                    // starts), during one (which carries on closing as it turns), or after the loop has let go.
                    var spinning = false
                    var holdJob: Job? = null
                    val waitForHold = scope.launch {
                        delay(HOLD_DELAY_MS)
                        holding = true
                        holdJob = scope.launch {
                            hold(headSweep, haptics)
                            closed = true
                            // Its own job, so letting go of the screen doesn't stop it.
                            scope.launch { letGo(spin = !spinning) }
                        }
                    }
                    var lastAngle = angleOf(down.position)
                    val samples = ArrayDeque<Pair<Long, Float>>().apply { addLast(down.uptimeMillis to turned) }
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        // While holding, a finger's natural wobble shouldn't turn it, so it takes a little more.
                        if (!spinning && (change.position - down.position).getDistance() > if (holding) holdSlop else slop) {
                            spinning = true
                            dragging = true
                            waitForHold.cancel()
                            if (holding) {
                                // The hold carries on: only lifting the finger stops it. Turn from here and from where
                                // it is, which a let-go may have spun it to.
                                lastAngle = angleOf(change.position)
                                turned = rotation.value
                                samples.clear()
                                samples.addLast(change.uptimeMillis to turned)
                            }
                        }
                        if (spinning) {
                            val angle = angleOf(change.position)
                            val step = wrapDegrees(angle - lastAngle)
                            turned += step
                            if (step != 0f) spinDirection = sign(step)
                            lastAngle = angle
                            val target = turned
                            scope.launch { rotation.snapTo(target) }
                            samples.addLast(change.uptimeMillis to turned)
                            while (samples.size > 2 && change.uptimeMillis - samples.first().first > VELOCITY_WINDOW_MS) samples.removeFirst()
                            dragSpeed = speedOf(samples)
                        }
                        change.consume()
                    }
                    waitForHold.cancel()
                    touched = false
                    dragging = false
                    if (holding && !closed) {
                        holdJob?.cancel()
                        // The head draws back to the open ensō: a quick ease-out, no overshoot.
                        scope.launch { headSweep.animateTo(0f, tween(RELEASE_MS, easing = LinearOutSlowInEasing)) }
                    }
                    val velocity = if (spinning) speedOf(samples) else 0f
                    dragSpeed = 0f
                    if (velocity != 0f) spinDirection = sign(velocity)
                    // Thrown, let go, or caught mid-spin, it finds its own way back to rest. A let-go's spin carries on.
                    if (spinning || (!atRest(rotation.value) && !rotation.isRunning)) {
                        scope.launch { rotation.spinHome(velocity, spinDirection) }
                    }
                }
            },
    ) {
        val unit = size.width / MARK_BOX
        val sweep = headSweep.value
        val alpha = (inkAlpha + if (touched) 0f else breath * BREATH_DEPTH).coerceIn(0f, 1f)
        val ink = EnsoColors.TextPrimary
        withTransform({
            scale(unit, unit, pivot = Offset.Zero)
            translate(-MARK_ORIGIN, -MARK_ORIGIN)
        }) {
            drawGlow(glow, sweep)
            val path = stroke.at(sweep)
            // The let-go's bounce.
            val pop = bounce.value
            // Two fading copies trail a fast spin, like motion blur.
            val speed = if (dragging) dragSpeed else rotation.velocity
            val blur = ((abs(speed) - GHOST_FROM) / (GHOST_FULL - GHOST_FROM)).coerceIn(0f, 1f)
            if (blur > 0f) {
                GHOSTS.forEach { (lag, strength) ->
                    withTransform({
                        scale(pop, pop, Offset(CENTER, CENTER))
                        rotate(rotation.value - speed * lag, Offset(CENTER, CENTER))
                    }) {
                        drawPath(path, ink, alpha = alpha * strength * blur)
                    }
                }
            }
            withTransform({
                scale(pop, pop, Offset(CENTER, CENTER))
                rotate(rotation.value, Offset(CENTER, CENTER))
            }) {
                // One layer, faded as a whole: the ink is translucent, and the strands drawn on their own would darken
                // where they lie over the stroke, as they do once the head's ink has looped over them.
                drawIntoCanvas { it.saveLayer(GridBounds, Paint().apply { this.alpha = alpha }) }
                drawPath(path, ink)
                Strands.forEach { drawPath(it, ink) }
                drawIntoCanvas { it.restore() }
            }
        }
    }
}

/**
 * Holding it: the thick head keeps extending, sweeping back round (anticlockwise) into the thin tail and on, faster as it
 * goes, laying ink at its own width over the stroke. The gap closes as the head reaches the tail's end, about 1.3 s in;
 * by about 3.5 s it has gone a full loop and the ensō reads as a solid circle. A rumble rises with it, with a click as
 * the gap closes and a heavier one when the loop is complete. A hold that starts mid-release picks up where it is.
 */
private suspend fun CoroutineScope.hold(sweep: Animatable<Float, AnimationVector1D>, haptics: FidgetHaptics) {
    val rumble = launch {
        while (true) {
            haptics.tick(0.08f + 0.42f * sweep.value / FULL_SWEEP)
            delay(RUMBLE_MS)
        }
    }
    try {
        // As if it had been sweeping from rest at a steady acceleration to get where it is.
        val already = sqrt(2f * sweep.value / SWEEP_ACCELERATION)
        val began = withFrameNanos { it }
        var closed = sweep.value >= GAP_SWEEP
        while (sweep.value < FULL_SWEEP) {
            val seconds = already + (withFrameNanos { it } - began) / 1e9f
            val next = min(FULL_SWEEP, SWEEP_ACCELERATION * seconds * seconds / 2f)
            sweep.snapTo(next)
            if (!closed && next >= GAP_SWEEP) {
                closed = true
                haptics.click(heavy = false)
            }
        }
        rumble.cancel()
        haptics.click(heavy = true)
    } finally {
        rumble.cancel()
    }
}

/** A soft glow in the space's colors behind the ensō, rising as the head sweeps round, to 50% at a full loop. */
private fun DrawScope.drawGlow(colors: List<Color>, sweep: Float) {
    if (sweep <= 0f || colors.isEmpty()) return
    val strength = GLOW_FULL * min(1f, sweep / FULL_SWEEP)
    drawCircle(
        Brush.radialGradient(*glowStops(colors, strength), center = Offset(CENTER, CENTER), radius = GLOW_RADIUS),
        radius = GLOW_RADIUS,
        center = Offset(CENTER, CENTER),
    )
}

/**
 * The glow's rings: the first color (the accent) in the middle at [strength], fading outward through the others, then
 * out to nothing in the last one. With one color it's that color at full strength, half strength by 55% of the way,
 * and gone at the edge.
 */
private fun glowStops(colors: List<Color>, strength: Float): Array<Pair<Float, Color>> {
    val last = colors.last()
    if (colors.size == 1) return arrayOf(0f to last.copy(alpha = strength), 0.55f to last.copy(alpha = strength * 0.55f), 1f to last.copy(alpha = 0f))
    val rings = colors.mapIndexed { i, color ->
        val along = i.toFloat() / (colors.size - 1)
        // The colors share the inner 65%, each a little fainter than the one inside it.
        GLOW_COLORS_REACH * along to color.copy(alpha = strength * (1f - 0.45f * along))
    }
    return (rings + (1f to last.copy(alpha = 0f))).toTypedArray()
}

/**
 * The ensō's main brush stroke, with its head extended [sweep] degrees, built from the stroke's own formula rather
 * than drawn from the SVG, so it can grow. At 0 it lies within the SVG's rounding (0.66 grid units, about 0.4 dp) of
 * enso-mark.svg's main stroke, which this formula made.
 *
 * The stroke sweeps from 124° to 432° around the center. Its radius wobbles out from 152 as it goes, and its width
 * tapers from 74 to 2.5, with a slight swell near the start. Holding extends the head back from 124° at the head's own
 * radius and width, as more ink at the start of the stroke, so the stroke itself never changes under it. The thick
 * start has a round cap, which travels with the head.
 */
private fun Path.ensoStroke(sweep: Float) {
    reset()
    val start = Math.toRadians(START_DEGREES.toDouble())
    val head = start - Math.toRadians(sweep.toDouble())
    val end = Math.toRadians(END_DEGREES.toDouble())
    fun radius(t: Double) = RADIUS + 6 * t + 3 * sin(2.2 * PI * t)
    fun width(t: Double) = ((74 - 2.5) * (1 - t).pow(1.15) + 2.5) * (1 + 0.06 * exp(-t / 0.15))
    fun point(angle: Double, distance: Double) = Offset((CENTER + distance * cos(angle)).toFloat(), (CENTER + distance * sin(angle)).toFloat())
    val headWidth = width(0.0)
    // The head's extension, sampled as densely as the stroke.
    val extension = ceil(sweep / (END_DEGREES - START_DEGREES) * SAMPLES).toInt()
    fun extensionAngle(j: Int) = head + (start - head) * j / extension

    // The outer edge forwards, extension first, then the inner edge back.
    for (j in 0 until extension) {
        val p = point(extensionAngle(j), RADIUS + headWidth / 2)
        if (j == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    for (i in 0..SAMPLES) {
        val t = i.toDouble() / SAMPLES
        val p = point(start + (end - start) * t, radius(t) + width(t) / 2)
        if (i == 0 && extension == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    for (i in SAMPLES downTo 0) {
        val t = i.toDouble() / SAMPLES
        val p = point(start + (end - start) * t, radius(t) - width(t) / 2)
        lineTo(p.x, p.y)
    }
    for (j in extension - 1 downTo 0) {
        val p = point(extensionAngle(j), RADIUS - headWidth / 2)
        lineTo(p.x, p.y)
    }
    // The round cap at the head: half a disc behind it, from the inner edge round to the outer one.
    val half = headWidth / 2
    val center = point(head, RADIUS)
    val normal = Offset(cos(head).toFloat(), sin(head).toFloat())
    val tangent = Offset(-sin(head).toFloat(), cos(head).toFloat())
    for (k in 1 until CAP_SAMPLES) {
        val phi = PI * k / CAP_SAMPLES
        val across = -cos(phi) * half
        val back = -sin(phi) * half
        lineTo(
            (center.x + normal.x * across + tangent.x * back).toFloat(),
            (center.y + normal.y * across + tangent.y * back).toFloat(),
        )
    }
    close()
}

/** The last stroke built, so a still ensō doesn't rebuild it every frame. */
private class StrokeCache {
    private val path = Path()
    private var builtAt = Float.NaN

    fun at(sweep: Float): Path {
        if (sweep != builtAt) {
            path.ensoStroke(sweep)
            builtAt = sweep
        }
        return path
    }
}

/** The two thin dry-brush strands near the tail, from docs/brand/enso-mark.svg as they are. */
private val Strands: List<Path> = listOf(
    "M231 77 L242 75 L253 74 L265 74 L276 75 L287 77 L298 79 L309 82 L320 85 L330 89 L340 94 L350 100 L360 106 L369 113 L378 120 L386 128 L394 136 L401 145 L407 154 L413 164 L419 174 L423 184 L428 195 L431 205 L434 216 L436 228 L437 239 L438 250 L438 261 L437 273 L435 284 L433 295 L430 306 L427 316 L423 327 L418 337 L412 347 L406 356 L400 365 L392 374 L385 382 L376 390 L368 397 L359 403 L349 409 L339 414 L329 419 L319 423 L308 426 L298 429 L287 430 L287 430 L297 428 L308 424 L318 421 L328 416 L338 412 L348 406 L357 400 L365 394 L374 386 L381 379 L389 371 L396 362 L402 353 L408 344 L413 335 L418 325 L422 315 L425 304 L428 294 L430 283 L431 272 L432 261 L432 250 L431 239 L430 228 L428 218 L425 207 L422 197 L418 186 L414 176 L408 167 L403 157 L396 148 L390 140 L382 132 L374 124 L366 117 L357 110 L348 104 L338 98 L329 93 L318 89 L308 85 L297 82 L286 79 L275 78 L264 77 L253 76 L242 76 L231 77Z",
    "M384 141 L389 147 L394 153 L399 160 L403 166 L407 173 L411 181 L414 188 L417 195 L420 203 L422 211 L424 219 L426 227 L427 235 L428 243 L428 251 L428 259 L427 267 L427 275 L426 283 L424 290 L422 298 L420 306 L417 314 L414 321 L411 328 L407 335 L403 342 L399 349 L394 356 L389 362 L384 368 L379 374 L373 379 L367 384 L361 389 L354 394 L348 398 L341 402 L334 406 L327 409 L319 412 L312 415 L304 417 L297 419 L289 420 L281 421 L274 422 L266 423 L258 422 L250 422 L250 422 L258 422 L266 421 L273 421 L281 420 L289 418 L296 417 L304 415 L311 412 L318 410 L326 406 L333 403 L339 399 L346 395 L352 391 L359 386 L365 382 L371 376 L376 371 L381 365 L386 359 L391 353 L396 347 L400 340 L404 334 L407 327 L411 319 L414 312 L416 305 L418 297 L420 290 L422 282 L423 274 L424 266 L424 259 L424 251 L424 243 L423 235 L422 227 L421 219 L419 212 L417 204 L415 196 L412 189 L409 182 L406 174 L402 167 L398 161 L393 154 L389 147 L384 141Z",
).map { PathParser().parsePathString(it).toPath() }

/**
 * After a spin, the way home: friction slows it as far as [CRUISE_SPEED], then it carries on at that slow, even speed,
 * the way it was turning, until it's at rest as the logo again (a whole number of turns), easing in over the last few
 * degrees. A touch stops it on the way.
 */
private suspend fun Animatable<Float, AnimationVector1D>.spinHome(velocity: Float, direction: Float) {
    if (abs(velocity) > CRUISE_SPEED) {
        animateDecay(velocity, exponentialDecay(frictionMultiplier = FRICTION, absVelocityThreshold = CRUISE_SPEED))
    }
    val turns = value / FULL_TURN
    val home = FULL_TURN * if (direction > 0f) ceil(turns) else floor(turns)
    val left = abs(home - value)
    if (left < AT_REST_DEGREES) {
        snapTo(home)
        return
    }
    val settle = min(SETTLE_DEGREES, left)
    if (left > settle) {
        animateTo(home - direction * settle, tween(((left - settle) / CRUISE_SPEED * 1000).roundToInt(), easing = LinearEasing))
    }
    // Starts at the cruising speed (the curve's slope is 2 at the start) and slows to a stop on the mark.
    animateTo(home, tween((2 * settle / CRUISE_SPEED * 1000).roundToInt(), easing = SettleEasing))
}

/**
 * The spin a let-go starts with: enough that friction carries it to the next whole turn at least [UNWIND_MIN_DEGREES]
 * away, slowing to the cruising speed just before, so it eases in at rest. Friction takes (v − cruise) / rate degrees
 * to slow from v to the cruise, at [DECAY_RATE].
 */
private fun unwindKick(from: Float): Float {
    val home = FULL_TURN * ceil((from + UNWIND_MIN_DEGREES) / FULL_TURN)
    return CRUISE_SPEED + DECAY_RATE * (home - from - SETTLE_DEGREES)
}

private fun atRest(degrees: Float) = abs(degrees - FULL_TURN * kotlin.math.round(degrees / FULL_TURN)) < AT_REST_DEGREES

private val SettleEasing = Easing { 1f - (1f - it) * (1f - it) }

/** Turns, in degrees a second, over the last few samples of the drag. */
private fun speedOf(samples: ArrayDeque<Pair<Long, Float>>): Float {
    if (samples.size < 2) return 0f
    val (t0, a0) = samples.first()
    val (t1, a1) = samples.last()
    return if (t1 > t0) (a1 - a0) * 1000f / (t1 - t0) else 0f
}

/** An angle change brought into -180..180°, so crossing the left edge (where atan2 jumps) doesn't spin it round. */
private fun wrapDegrees(degrees: Float): Float = ((degrees + 540f) % 360f) - 180f

/**
 * The fidget's haptics: Android 12's composition primitives where the motor supports them, plain haptic feedback
 * otherwise (which can't rumble, so the hold is quiet there).
 */
private class FidgetHaptics(context: Context, private val view: View) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val primitives = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    private val thud = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) == true

    fun tick(scale: Float) {
        if (primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator?.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale.coerceIn(0f, 1f)).compose())
        } else if (scale >= SPIN_TICK_SCALE) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun click(heavy: Boolean) {
        if (primitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val composition = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, if (heavy) 1f else 0.7f)
            if (heavy && thud) composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 20)
            vibrator?.vibrate(composition.compose())
        } else {
            view.performHapticFeedback(if (heavy) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

private val FidgetSize = 240.dp
private val HOLD_SLOP = 6.dp

/** enso-mark.svg's box within the 512 grid: from 50, 412 across. */
private const val MARK_ORIGIN = 50f

/** The whole 512 grid, as the ensō's layer. */
private val GridBounds = Rect(0f, 0f, 512f, 512f)
private const val MARK_BOX = 412f
private const val CENTER = 256f

/** Distances from the center, in grid units, where a touch counts as on the circle: the stroke and a finger's slack. */
private val TOUCH_BAND = 100f..200f

private const val START_DEGREES = 124f
private const val END_DEGREES = 432f
private const val RADIUS = 152.0
private const val SAMPLES = 140
private const val CAP_SAMPLES = 24

private const val IDLE_ALPHA = 0.25f
private const val TOUCHED_ALPHA = 0.85f
private const val INK_FADE_MS = 220

/** The slow breathing at rest: ±3% over about 8 seconds, there and back. */
private const val BREATH_DEPTH = 0.03f
private const val BREATH_MS = 4000

/** ×0.975 a frame at 60 fps is e^(−1.52 t); Compose's exponential decay runs at 4.2 × this multiplier. */
private const val FRICTION = 0.362f

/** How fast friction slows it: Compose's exponential decay runs at 4.2 × its friction multiplier, per second. */
private const val DECAY_RATE = 4.2f * FRICTION

/** How fast it goes home once friction has slowed it, in degrees a second: a turn every 9 seconds. */
private const val CRUISE_SPEED = 40f
private const val SETTLE_DEGREES = 12f
private const val AT_REST_DEGREES = 0.05f
private const val FULL_TURN = 360f
private const val VELOCITY_WINDOW_MS = 90L

/** Ghosts show from this spin speed (degrees a second) and are at full strength by the next. */
private const val GHOST_FROM = 180f
private const val GHOST_FULL = 900f

/** The ghosts' lag behind the stroke, in seconds of spin, and how strong each is. */
private val GHOSTS = listOf(0.035f to 0.35f, 0.07f to 0.18f)

private const val TICK_DEGREES = 30f
private const val TICK_GAP_MS = 25L
private const val SPIN_TICK_SCALE = 0.6f

private const val HOLD_DELAY_MS = 350L
private const val RELEASE_MS = 320

/** How far the finger must move during a hold to start spinning it too. */
private val HOLD_BREAK_SLOP = 14.dp

/** The let-go after a closed loop: the head unwinds this fast, the spin goes at least this far, and the mark bounces. */
private const val UNWIND_MS = 450
private const val UNWIND_MIN_DEGREES = 300f
private const val BOUNCE_VELOCITY = 1.4f
private const val BOUNCE_DAMPING = 0.3f
private const val BOUNCE_STIFFNESS = 380f
private const val RUMBLE_MS = 45L

/** The head reaches where the tail ends (72°, from 124°), closing the gap. */
private const val GAP_SWEEP = 52f

/** A full loop: the head's ink then goes all the way round. */
private const val FULL_SWEEP = 360f

/** Degrees a second, each second: the gap closes about 1.3 s in, and the loop is complete at about 3.5 s. */
private const val SWEEP_ACCELERATION = 60f

private const val GLOW_FULL = 0.50f

/** How far out, as a fraction of the glow, the space's colors reach before it fades to nothing. */
private const val GLOW_COLORS_REACH = 0.65f
private const val GLOW_RADIUS = 340f
