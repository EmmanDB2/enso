package app.enso.browser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** A page captured for a frosted backdrop: a small blurred copy ([frostedCopy]), and where the page sat in the window. */
class FrostedPage(val image: ImageBitmap, val bounds: Rect)

/**
 * The frosted backdrop of the surfaces inside it (spec: the tab sheet, board 4). Every [Modifier.spaceSurface] draws
 * the same picture of the whole window ([frosted]), lined up with where it sits, so the sheet and the rows sliding over
 * it are the same pixels. One layer each matters: rows clip to rounded corners, and layering the page and the colors
 * separately left their softened edges showing as outlines.
 *
 * Until the capture has eased in, the plain colors cover it at [plainColorsAlpha], read while drawing.
 */
@Stable
class SurfaceBackdrop(val page: FrostedPage, val plainColorsAlpha: () -> Float) {
    private var cached: Triple<SpaceColors, IntSize, ImageBitmap>? = null

    /** The page frosted with [colors] across a window of [windowSize], at an eighth of its size. */
    fun frosted(colors: SpaceColors, windowSize: IntSize): ImageBitmap {
        cached?.let { (cachedColors, cachedSize, image) -> if (cachedColors == colors && cachedSize == windowSize) return image }
        return frostedWindow(page, colors, windowSize).also { cached = Triple(colors, windowSize, it) }
    }
}

val LocalSurfaceBackdrop = compositionLocalOf<SurfaceBackdrop?> { null }

/**
 * [content]'s surfaces frosted over [page] (the tab sheet over the page it opened on). Until the capture arrives
 * they're their plain colors; then the colors ease back so the page shows through.
 */
@Composable
fun FrostedOver(page: FrostedPage?, content: @Composable () -> Unit) {
    val plainColors = remember { Animatable(1f) }
    LaunchedEffect(page) {
        plainColors.snapTo(1f)
        if (page != null) plainColors.animateTo(0f, tween(FROST_IN_MS))
    }
    val backdrop = remember(page) { page?.let { SurfaceBackdrop(it) { plainColors.value } } }
    CompositionLocalProvider(LocalSurfaceBackdrop provides backdrop, content = content)
}

/** The window's space colors, the blurred page where it sat, and the colors again at [FROSTED_COLORS] over it. */
private fun frostedWindow(page: FrostedPage, colors: SpaceColors, windowSize: IntSize): ImageBitmap {
    val width = (windowSize.width / BACKDROP_SCALE).coerceAtLeast(1)
    val height = (windowSize.height / BACKDROP_SCALE).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // The same gradient as [SpaceColors.brush] across the window: stops evenly from top to bottom.
    val paint = Paint().apply {
        val stops = colors.stops.map { it.toArgb() }
        if (stops.size >= 2) shader = LinearGradient(0f, 0f, 0f, height.toFloat(), stops.toIntArray(), null, Shader.TileMode.CLAMP)
        else color = stops.firstOrNull() ?: 0
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    val scale = 1f / BACKDROP_SCALE
    val bounds = page.bounds
    val at = RectF(bounds.left * scale, bounds.top * scale, bounds.right * scale, bounds.bottom * scale)
    canvas.drawBitmap(page.image.asAndroidBitmap(), null, at, Paint(Paint.FILTER_BITMAP_FLAG))
    paint.alpha = (FROSTED_COLORS * 255).roundToInt()
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return bitmap.asImageBitmap()
}

/**
 * A blurred copy of [page], an eighth of its size: box-blurred three times, which comes close to a Gaussian, and
 * smoothed further when it's drawn back at full size. Blurring it once beats blurring every surface live, and works
 * before Android 12, which `Modifier.blur` needs.
 */
fun frostedCopy(page: Bitmap): ImageBitmap {
    val readable = if (page.config == Bitmap.Config.HARDWARE) page.copy(Bitmap.Config.ARGB_8888, false) else page
    val width = (readable.width / BACKDROP_SCALE).coerceAtLeast(1)
    val height = (readable.height / BACKDROP_SCALE).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(readable, width, height, true)
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    val scratch = IntArray(pixels.size)
    repeat(BLUR_PASSES) {
        for (y in 0 until height) blurLine(pixels, scratch, start = y * width, step = 1, count = width)
        for (x in 0 until width) blurLine(scratch, pixels, start = x, step = width, count = height)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One box-blur pass along a row or column ([step] apart), with a running sum and the edges held. */
private fun blurLine(source: IntArray, target: IntArray, start: Int, step: Int, count: Int) {
    val window = 2 * BLUR_RADIUS + 1
    fun at(i: Int) = source[start + i.coerceIn(0, count - 1) * step]
    var alpha = 0
    var red = 0
    var green = 0
    var blue = 0
    for (i in -BLUR_RADIUS..BLUR_RADIUS) {
        val pixel = at(i)
        alpha += pixel ushr 24
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    for (i in 0 until count) {
        target[start + i * step] = ((alpha / window) shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
        val leaving = at(i - BLUR_RADIUS)
        val entering = at(i + BLUR_RADIUS + 1)
        alpha += (entering ushr 24) - (leaving ushr 24)
        red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
        green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
        blue += (entering and 0xFF) - (leaving and 0xFF)
    }
}

/** How opaque the space colors stay over the frosted page: enough for rows to read over any page. */
private const val FROSTED_COLORS = 0.8f
private const val FROST_IN_MS = 220

private const val BACKDROP_SCALE = 8

/** At an eighth of the size, three passes of this make about the edge wave's 20 dp blur. */
private const val BLUR_RADIUS = 5
private const val BLUR_PASSES = 3
