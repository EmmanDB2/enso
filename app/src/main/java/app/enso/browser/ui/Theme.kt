package app.enso.browser.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.enso.browser.R

/**
 * Ensō's palette: the spec's "Visual style" table and its dark values (step 10). [dark] picks between them. The
 * activity sets it from Android's dark theme and [app.enso.browser.Settings.themeMode]. It's snapshot state, so a
 * composable that reads a color recomposes when it changes; views that took a color once must be given it again.
 */
object EnsoColors {
    var dark by mutableStateOf(false)

    private fun pick(light: Color, dark: Color) = if (this.dark) dark else light

    /** Dark is Zen's own dark base (rgb 23, 23, 26), so spaces' colors over it match Zen's. */
    val SurfaceBase get() = pick(Color(0xFFECE7EA), Color(0xFF17171A))
    val Page get() = pick(Color(0xFFFAF9F7), Color(0xFF1E1D22))
    val TextPrimary get() = pick(Color(0xFF26232B), Color(0xFFECEAF0))
    val TextSecondary get() = pick(Color(0xFF5F5B66), Color(0xFFA9A5B0))
    val FieldFill get() = TextPrimary.copy(alpha = if (dark) 0.10f else 0.07f)
    val CurrentTab get() = pick(Color.White, Color(0xFF2E2D33))

    /** Cards and menus over everything else: the sync sheet, prompts, the palette, hold menus. */
    val Card get() = pick(Color.White, Color(0xFF2A292F))

    /** A row lit softly, such as a pinned tab on screen. */
    val SoftHighlight get() = pick(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))

    /** Buttons and fields in the bottom bar and its panels. */
    val PillFill get() = pick(Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.08f))

    /** Icons and text on a [TextPrimary] fill, such as the bar's ↓ while a panel is open. */
    val OnTextPrimary get() = pick(Color.White, Color(0xFF17171A))

    /**
     * The circle behind a favicon that would vanish on these surfaces: a mostly white one in light, a mostly black
     * one in dark. Other favicons have none.
     */
    val FaviconContrastCircle get() = pick(Color(0xFF6F6B76), Color(0xFFC9C5CE))

    /** The gold of Quick Tabs: deeper on light surfaces, brighter on dark ones (spec: visual style). */
    val QuickTabOutline get() = pick(Color(0xFFE0A800), Color(0xFFF2C94C))

    /** The Quick Tabs stack's front card. */
    val QuickTabCard get() = pick(Color(0xFFFFF8EA), Color(0xFF332E24))
    val CloseSwipe = Color(0xFFD6404A)
    val CloseMenuText get() = pick(Color(0xFFC4323C), Color(0xFFFF6B73))

    val SpaceAccents = listOf(
        Color(0xFF4F7CFF),
        Color(0xFF9B7BFF),
        Color(0xFF2BB5A0),
        Color(0xFFF0A43A),
        Color(0xFFFF7A6B),
    )

    /** Used until spaces exist (build step 3). */
    val DefaultSpaceAccent = SpaceAccents[0]

    /** A space's surface color: its accent at 16% over the base. */
    fun spaceTint(accent: Color) = lerp(SurfaceBase, accent, 0.16f)

    // The space menu (board 3).

    /** The frost over the blurred page. */
    val WaveFrost get() = Page.copy(alpha = 0.62f)

    /** The selected space's pill. */
    val WavePill get() = pick(Color.White.copy(alpha = 0.78f), Card.copy(alpha = 0.85f))

    /** The ink brush stroke along the wave. */
    val WaveInk get() = TextPrimary.copy(alpha = 0.20f)

    /** Space names and icons on the wave. Rows farther from the thumb fade it. */
    val WaveText get() = TextPrimary
}

/**
 * Material's defaults for what Ensō doesn't color itself (the folder name dialog, plain text, ripples): the light
 * scheme as before, or the dark one with Ensō's surfaces. Every Compose view's content goes inside.
 */
@Composable
fun EnsoTheme(content: @Composable () -> Unit) {
    val scheme = if (EnsoColors.dark) {
        darkColorScheme(
            background = EnsoColors.Page,
            surface = EnsoColors.Card,
            surfaceContainer = EnsoColors.Card,
            surfaceContainerHigh = EnsoColors.Card,
            onBackground = EnsoColors.TextPrimary,
            onSurface = EnsoColors.TextPrimary,
            onSurfaceVariant = EnsoColors.TextSecondary,
        )
    } else {
        lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides EnsoColors.TextPrimary, content = content)
    }
}

private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Text styles. Getters, so their colors follow [EnsoColors.dark]. */
object EnsoType {
    val Row get() = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = EnsoColors.TextPrimary)
    val RowTitle get() = Row.copy(fontWeight = FontWeight.Medium)
    val MenuItem get() = Row.copy(fontWeight = FontWeight.Medium)
    val SpaceName get() = Row.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val Secondary get() = Row.copy(fontSize = 15.sp, color = EnsoColors.TextSecondary)
    val Url get() = Row.copy(fontSize = 14.sp, color = EnsoColors.TextSecondary)
}
