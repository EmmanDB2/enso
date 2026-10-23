package app.enso.browser.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.toolbar.AddressInput

/** Pastel circle fills from the mockups' letter placeholders. */
private val FaviconFills = listOf(
    Color(0xFFF6B9C6),
    Color(0xFFA4B5FF),
    Color(0xFFFFD48A),
    Color(0xFFA8E6D3),
    Color(0xFFD6C6FF),
)

/** Letter placeholder in a circle, until real favicons (build step 10). */
@Composable
fun LetterFavicon(title: String, url: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val host = AddressInput.displayHost(url)
    val letter = (title.ifBlank { host }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '·'
    val fill = FaviconFills[Math.floorMod(host.hashCode(), FaviconFills.size)]
    Box(modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            letter.toString(),
            style = EnsoType.Row.copy(fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** Line icons for the spaces in the mockups. Synced Zen icons get mapped onto these in step 5. */
object SpaceIcons {
    private val icons = mapOf(
        "home" to R.drawable.ic_space_home,
        "school" to R.drawable.ic_space_school,
        "code" to R.drawable.ic_space_code,
        "moon" to R.drawable.ic_space_moon,
        "book" to R.drawable.ic_space_book,
        "server" to R.drawable.ic_space_server,
    )

    fun drawable(key: String): Int? = icons[key]
}

/** A space's icon: one of [SpaceIcons], or the emoji the user set. */
@Composable
fun SpaceIcon(icon: String, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = EnsoColors.TextPrimary) {
    val drawable = SpaceIcons.drawable(icon)
    if (drawable != null) {
        Icon(painterResource(drawable), contentDescription = null, modifier = modifier.size(size), tint = tint)
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(icon, style = EnsoType.Row.copy(fontSize = (size.value * 0.8f).sp))
        }
    }
}
