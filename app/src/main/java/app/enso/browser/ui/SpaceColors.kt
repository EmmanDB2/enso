package app.enso.browser.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import app.enso.browser.spaces.Space
import kotlin.math.roundToInt

/**
 * How a space colors Ensō's chrome (spec: Visual style): one gradient down the whole window, from its Zen
 * theme's colors laid over the base at the theme's own opacity. A theme with no colors gives the accent at 16%.
 * [accent] stays for small marks such as the load line and folder icons.
 */
@Immutable
data class SpaceColors(
    /** The surface colors, top to bottom, already laid over the base. */
    val stops: List<Color>,
    val accent: Color,
) {
    /** The gradient for a surface whose top sits at [top] in a window [windowHeight] tall. */
    fun brush(top: Float, windowHeight: Float): Brush =
        if (stops.size < 2) {
            SolidColor(stops.firstOrNull() ?: Color.Transparent)
        } else {
            Brush.verticalGradient(stops, startY = -top, endY = windowHeight - top)
        }
}

/** The chrome colors of this space. */
fun Space.colors(): SpaceColors {
    val accent = Color(accent)
    val stops = gradient.map { lerp(EnsoColors.SurfaceBase, Color(it), themeOpacity) }
    return SpaceColors(stops.ifEmpty { listOf(EnsoColors.spaceTint(accent)) }, accent)
}

/** The faint tint of this space's blank page (the start screen): its colors at 10% over the page. */
fun Space.pageColors(): SpaceColors {
    val accent = Color(accent)
    val stops = gradient.ifEmpty { listOf(this.accent) }.map { lerp(EnsoColors.Page, Color(it), PAGE_TINT) }
    return SpaceColors(stops, accent)
}

/**
 * Paints this surface with [colors]' window-wide gradient, lined up with where the surface sits in the window,
 * so neighboring surfaces (and rows moving over them) read as one. Inside a [LocalSurfaceBackdrop] (the tab sheet)
 * the blurred page shows through under the colors, lined up the same way.
 */
fun Modifier.spaceSurface(colors: SpaceColors, shape: Shape = RectangleShape): Modifier = composed {
    val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val backdrop = LocalSurfaceBackdrop.current
    var position by remember { mutableStateOf(Offset.Zero) }
    val positioned = onGloballyPositioned { position = it.positionInWindow() }
    if (backdrop == null) {
        positioned.background(colors.brush(position.y, windowHeight), shape)
    } else {
        val windowSize = LocalWindowInfo.current.containerSize
        val frosted = backdrop.frosted(colors, windowSize)
        positioned.drawWithCache {
            val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            onDrawBehind {
                clipPath(outline) {
                    drawImage(
                        frosted,
                        dstOffset = IntOffset(-position.x.roundToInt(), -position.y.roundToInt()),
                        dstSize = windowSize,
                        filterQuality = FilterQuality.Low,
                    )
                    val plain = backdrop.plainColorsAlpha()
                    if (plain > 0f) drawRect(colors.brush(position.y, windowHeight), alpha = plain)
                }
            }
        }
    }
}

private const val PAGE_TINT = 0.10f
