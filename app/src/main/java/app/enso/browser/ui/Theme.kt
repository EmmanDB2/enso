package app.enso.browser.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.enso.browser.R

/** Starting palette from the spec's "Visual style" table. */
object EnsoColors {
    val SurfaceBase = Color(0xFFECE7EA)
    val Page = Color(0xFFFAF9F7)
    val TextPrimary = Color(0xFF26232B)
    val TextSecondary = Color(0xFF5F5B66)
    val FieldFill = TextPrimary.copy(alpha = 0.07f)
    val CurrentTab = Color.White
    val QuickTabOutlineOnDark = Color(0xFFF2C94C)
    val QuickTabOutlineOnLight = Color(0xFFE0A800)
    val CloseSwipe = Color(0xFFD6404A)
    val CloseMenuText = Color(0xFFC4323C)

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
}

private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

object EnsoType {
    val Row = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = EnsoColors.TextPrimary)
    val MenuItem = Row.copy(fontWeight = FontWeight.Medium)
}
