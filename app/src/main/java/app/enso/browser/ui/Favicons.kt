package app.enso.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.Tab
import app.enso.browser.toolbar.AddressInput

/** Pastel circle fills from the mockups' letter placeholders. */
private val FaviconFills = listOf(
    Color(0xFFF6B9C6),
    Color(0xFFA4B5FF),
    Color(0xFFFFD48A),
    Color(0xFFA8E6D3),
    Color(0xFFD6C6FF),
)

/**
 * Letter placeholder in a circle, until real favicons (build step 10). [ring] draws a ring around
 * it, such as the gold ring Quick Tabs get in search results.
 */
@Composable
fun LetterFavicon(
    title: String,
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    ring: Color? = null,
) {
    val host = AddressInput.displayHost(url)
    val letter = (title.ifBlank { host }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '·'
    val fill = FaviconFills[Math.floorMod(host.hashCode(), FaviconFills.size)]
    val circle = @Composable { circleModifier: Modifier ->
        Box(circleModifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                letter.toString(),
                style = EnsoType.Row.copy(fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold),
            )
        }
    }
    if (ring == null) {
        circle(modifier)
    } else {
        Box(modifier.size(size + 10.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            circle(Modifier)
        }
    }
}

/** A tab's name for rows: its title, else its domain, else "New tab". */
@Composable
fun Tab.displayTitle(): String =
    title.ifBlank { AddressInput.displayHost(url) }.ifBlank { stringResource(R.string.new_tab_title) }

/** Line icons for the spaces in the mockups, by key. Zen's built-in icons map onto some of them ([ZenIcons]). */
object SpaceIcons {
    private val icons = mapOf(
        "home" to R.drawable.ic_space_home,
        "school" to R.drawable.ic_space_school,
        "code" to R.drawable.ic_space_code,
        "moon" to R.drawable.ic_space_moon,
        "book" to R.drawable.ic_space_book,
        "server" to R.drawable.ic_space_server,
    )

    /** A line icon for [icon]: one of these keys, or a Zen icon URL that maps to one. */
    fun drawable(icon: String): Int? = icons[ZenIcons.nameOf(icon)?.let { ZenIcons.lineIcons[it] } ?: icon]

    /** Text to show for [icon] when there's no line icon: the emoji itself, or one close to Zen's icon. */
    fun text(icon: String): String? = when (val zenName = ZenIcons.nameOf(icon)) {
        null -> icon.ifEmpty { null }
        else -> ZenIcons.emoji(zenName)
    }
}

/**
 * A space's icon: a line icon, an emoji, or (when the space has none Ensō can show) the first letter of
 * [fallbackName].
 */
@Composable
fun SpaceIcon(
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = EnsoColors.TextPrimary,
    fallbackName: String = "",
) {
    val drawable = SpaceIcons.drawable(icon)
    if (drawable != null) {
        Icon(painterResource(drawable), contentDescription = null, modifier = modifier.size(size), tint = tint)
        return
    }
    val text = SpaceIcons.text(icon)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (text != null) {
            Text(text, style = EnsoType.Row.copy(fontSize = (size.value * 0.8f).sp))
        } else {
            Text(
                fallbackName.take(1).uppercase(),
                style = EnsoType.Row.copy(fontSize = (size.value * 0.75f).sp, fontWeight = FontWeight.Bold, color = tint),
            )
        }
    }
}
