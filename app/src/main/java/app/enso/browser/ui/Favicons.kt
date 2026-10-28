package app.enso.browser.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.Tab
import app.enso.browser.toolbar.AddressInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.Icon as PageIcon
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow

/** Pastel circle fills from the mockups' letter placeholders. */
private val FaviconFills = listOf(
    Color(0xFFF6B9C6),
    Color(0xFFA4B5FF),
    Color(0xFFFFD48A),
    Color(0xFFA8E6D3),
    Color(0xFFD6C6FF),
)

/**
 * Where favicons come from (spec: visual style), best first: a loaded tab's page icon, which AC's icon module puts in
 * the store; Zen's icon for a synced tab; the icon module's caches; the site's `/favicon.ico`. A page with none of
 * these keeps its letter. Composables reach it through [LocalFavicons].
 */
class FaviconSource(
    private val icons: BrowserIcons,
    store: BrowserStore,
    /** Synced tabs' icons from Zen, by tab id. */
    val zenIcons: StateFlow<Map<String, String>>,
) {
    private val scope = MainScope()

    /** Loaded tabs' page icons, by tab id. */
    val pageIcons: StateFlow<Map<String, Bitmap>> = store.flow()
        .map { state -> state.tabs.mapNotNull { tab -> tab.content.icon?.let { tab.id to it } }.toMap() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Icons already found, so rows that come back (scrolling, a sheet reopening) draw them at once. */
    private val found = LruCache<String, Bitmap>(FOUND_ICONS)

    fun found(url: String, zenIcon: String?): Bitmap? = found.get(keyOf(url, zenIcon))

    /** The icon for a page that isn't loaded, or null when only its letter is left. */
    suspend fun load(url: String, zenIcon: String?): Bitmap? = try {
        val zen = zenIcon?.let { listOf(IconRequest.Resource(it, IconRequest.Resource.Type.FAVICON)) }.orEmpty()
        val icon = request(url, zen)
            // Nothing known for the page: the site's own default icon, which the module then remembers for it.
            ?: siteRoot(url)?.let { root -> request(url, listOf(IconRequest.Resource("$root/favicon.ico", IconRequest.Resource.Type.FAVICON))) }
        icon?.also { found.put(keyOf(url, zenIcon), it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load an icon for ${AddressInput.displayHost(url)}", e)
        null
    }

    /** The module's answer, unless it only made up a placeholder (its own letter). */
    private suspend fun request(url: String, resources: List<IconRequest.Resource>): Bitmap? =
        icons.loadIcon(IconRequest(url = url, resources = resources)).await()
            .takeUnless { it.source == PageIcon.Source.GENERATOR }
            ?.bitmap

    private fun keyOf(url: String, zenIcon: String?) = if (zenIcon == null) url else "$url ${zenIcon.hashCode()}"

    private fun siteRoot(url: String): String? {
        val uri = Uri.parse(url)
        return if (uri.scheme == "https" || uri.scheme == "http") "${uri.scheme}://${uri.authority}" else null
    }

    private companion object {
        const val TAG = "Favicons"
        const val FOUND_ICONS = 120
    }
}

/** Favicons for composables. Without one (previews), favicons are letters. */
val LocalFavicons = staticCompositionLocalOf<FaviconSource?> { null }

/** A tab's favicon: the page's own icon once it's loaded, Zen's for a synced tab, or the site's. */
@Composable
fun Favicon(tab: Tab, modifier: Modifier = Modifier, size: Dp = 24.dp, ring: Color? = null) =
    Favicon(tab.title, tab.url, tab.id, modifier, size, ring)

/**
 * A page's favicon on its own, or its letter while there's none ([LetterFavicon]). [tabId] is the tab showing it, if
 * any: a loaded tab has its page's icon, a synced one Zen's.
 */
@Composable
fun Favicon(title: String, url: String, tabId: String?, modifier: Modifier = Modifier, size: Dp = 24.dp, ring: Color? = null) {
    val source = LocalFavicons.current
    if (source == null) {
        LetterFavicon(title, url, modifier, size, ring)
        return
    }
    val pageIcons by source.pageIcons.collectAsState()
    val zenIcons by source.zenIcons.collectAsState()
    val pageIcon = tabId?.let { pageIcons[it] }
    val zenIcon = tabId?.let { zenIcons[it] }
    var loaded by remember(url, zenIcon) { mutableStateOf(source.found(url, zenIcon)) }
    LaunchedEffect(url, zenIcon, pageIcon == null) {
        if (pageIcon == null && loaded == null) loaded = source.load(url, zenIcon)
    }
    val bitmap = pageIcon ?: loaded
    if (bitmap == null) {
        LetterFavicon(title, url, modifier, size, ring)
        return
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // The user wanted no circles behind favicons, but a white mark (GitHub's, for dark themes) would vanish on the
    // light surfaces, so only those get a dark one.
    val light = remember(bitmap) { bitmap.isLight() }
    val icon = @Composable { iconModifier: Modifier ->
        Box(
            iconModifier.size(size).then(if (light) Modifier.background(EnsoColors.FaviconBackdropForLight, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val iconSize = size * if (light) FAVICON_IN_CIRCLE else FAVICON_SCALE
            Image(image, contentDescription = null, modifier = Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.18f)))
        }
    }
    if (ring == null) {
        icon(modifier)
    } else {
        Box(modifier.size(size + 10.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            icon(Modifier)
        }
    }
}

/** A favicon's size within the space a letter takes. */
private const val FAVICON_SCALE = 0.8f

/** A white favicon's size within its dark circle. */
private const val FAVICON_IN_CIRCLE = 0.66f

/** Whether an icon is mostly white or near it, judged on a 16-pixel copy with its transparent parts left out. */
private fun Bitmap.isLight(): Boolean {
    val readable = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val small = Bitmap.createScaledBitmap(readable, LIGHTNESS_SAMPLE, LIGHTNESS_SAMPLE, true)
    val pixels = IntArray(LIGHTNESS_SAMPLE * LIGHTNESS_SAMPLE)
    small.getPixels(pixels, 0, LIGHTNESS_SAMPLE, 0, 0, LIGHTNESS_SAMPLE, LIGHTNESS_SAMPLE)
    var weight = 0f
    var lightness = 0f
    for (pixel in pixels) {
        val alpha = android.graphics.Color.alpha(pixel) / 255f
        if (alpha < 0.1f) continue
        val luminance = (0.2126f * android.graphics.Color.red(pixel) + 0.7152f * android.graphics.Color.green(pixel) +
            0.0722f * android.graphics.Color.blue(pixel)) / 255f
        weight += alpha
        lightness += alpha * luminance
    }
    return weight > 0f && lightness / weight > LIGHT_ICON
}

private const val LIGHTNESS_SAMPLE = 16

/** Average lightness above which an icon counts as white. */
private const val LIGHT_ICON = 0.82f

/**
 * Letter placeholder in a circle: the fallback for a page without a favicon ([Favicon]). [ring] draws a ring around
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
