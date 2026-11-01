package app.enso.browser.start

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.displayTitle
import app.enso.browser.ui.pageColors
import app.enso.browser.ui.spaceSurface
import kotlinx.coroutines.delay
import mozilla.components.support.ktx.util.URLStringUtils
import kotlin.math.abs

/** A page from history that matches the palette's query. */
data class HistoryMatch(val url: String, val title: String)

/** What the start screen asks the app to do. */
interface StartScreenActions {
    /** Opens what was typed as a Quick Tab: the URL, or a web search. */
    fun search(input: String)

    fun switchTo(tabId: String)

    /** Opens a history page as a Quick Tab. */
    fun openHistory(url: String)

    fun openTabSheet()

    suspend fun history(query: String, limit: Int): List<HistoryMatch>
}

/**
 * Start screen (board 9): a blank page with a faint space tint, shown when no tab is open or the
 * app has been away a while. Tap for the search palette (board 9b); swipe down for the tab sheet.
 */
@Composable
fun StartScreen(
    spaces: SpacesState,
    statusBarInset: Dp,
    paletteOpen: Boolean,
    onPaletteOpenChange: (Boolean) -> Unit,
    actions: StartScreenActions,
    /** On the inner screen the palette sits centered on the page card (board 9c) instead of in the upper half. */
    centered: Boolean = false,
    /** The ensō fidget in the middle of the blank page (boards F1–F4). */
    showEnso: Boolean = false,
) {
    val accent = Color(spaces.currentSpace.accent)
    val openPalette by rememberUpdatedState { onPaletteOpenChange(true) }
    val openTabSheet by rememberUpdatedState(actions::openTabSheet)

    BackHandler(enabled = paletteOpen) { onPaletteOpenChange(false) }

    Box(
        Modifier
            .fillMaxSize()
            .spaceSurface(spaces.currentSpace.pageColors())
            .pointerInput(Unit) {
                detectTapOrSwipeDown(onTap = { openPalette() }, onSwipeDown = { openTabSheet() })
            },
    ) {
        if (showEnso && !paletteOpen) {
            // The accent (Zen's primary) leads, then the theme's other colors.
            val space = spaces.currentSpace
            val glow = remember(space.accent, space.gradient) { (listOf(space.accent) + space.gradient).distinct().map(::Color) }
            EnsoFidget(glow = glow, modifier = Modifier.align(Alignment.Center))
        }
        if (paletteOpen) {
            SearchPalette(
                spaces = spaces,
                accent = accent,
                statusBarInset = statusBarInset,
                centered = centered,
                actions = actions,
                onDismiss = { onPaletteOpenChange(false) },
            )
        }
    }
}

private suspend fun PointerInputScope.detectTapOrSwipeDown(onTap: () -> Unit, onSwipeDown: () -> Unit) {
    val threshold = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        var total = Offset.Zero
        var lifted = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // A consumed release is a cancel, such as the space menu taking over a hold.
                lifted = !change.isConsumed
                break
            }
            total += change.positionChange()
        }
        when {
            total.getDistance() <= viewConfiguration.touchSlop -> if (lifted) onTap()
            total.y > threshold && total.y > abs(total.x) -> onSwipeDown()
        }
    }
}

/** Command palette modeled on Zen's (board 9b): in the upper half, so the keyboard fits below, or [centered] (9c). */
@Composable
private fun SearchPalette(
    spaces: SpacesState,
    accent: Color,
    statusBarInset: Dp,
    centered: Boolean,
    actions: StartScreenActions,
    onDismiss: () -> Unit,
) {
    BlockSpaceMenu()
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<HistoryMatch>()) }
    val tabs = remember(spaces, query) { matchingTabs(spaces, query) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query) {
        if (query.isBlank()) {
            history = emptyList()
        } else {
            delay(80) // Let typing settle before querying.
            val openUrls = tabs.map { it.url }.toSet()
            history = actions.history(query.trim(), limit = 6).filterNot { it.url in openUrls }.take(4)
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.06f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        val top = if (centered) 12.dp else statusBarInset + maxHeight * 0.12f
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .align(if (centered) Alignment.Center else Alignment.TopCenter)
                .padding(top = top, start = 12.dp, end = 12.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight - top - 12.dp)
                .shadow(16.dp, shape)
                .background(EnsoColors.Card, shape)
                .clip(shape)
                // Taps inside the card shouldn't reach the dim layer and close it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            PaletteField(query, { query = it }, accent, focusRequester, onSubmit = { if (it.isNotBlank()) actions.search(it.trim()) })
            PaletteDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val urlLike = query.isNotBlank() && URLStringUtils.isURLLike(query.trim())
                PaletteRow(
                    leading = { CircleIcon(R.drawable.ic_search) },
                    title = if (urlLike) {
                        stringResource(R.string.palette_go_to, AddressInput.displayHost(URLStringUtils.toNormalizedURL(query.trim())))
                    } else {
                        stringResource(R.string.palette_search_web)
                    },
                    onClick = { if (query.isBlank()) focusRequester.requestFocus() else actions.search(query.trim()) },
                )
                tabs.forEach { tab ->
                    PaletteRow(
                        leading = {
                            Favicon(
                                tab,
                                size = 26.dp,
                                ring = if (tab.quick != null) EnsoColors.QuickTabOutline else null,
                            )
                        },
                        title = tab.displayTitle(),
                        trailing = { SwitchToTabChip() },
                        onClick = { actions.switchTo(tab.id) },
                    )
                }
                history.forEach { match ->
                    PaletteRow(
                        leading = { CircleIcon(R.drawable.ic_clock) },
                        title = if (match.title.isBlank()) {
                            AddressInput.displayHost(match.url)
                        } else {
                            stringResource(R.string.palette_history_row, match.title, AddressInput.displayHost(match.url))
                        },
                        onClick = { actions.openHistory(match.url) },
                    )
                }
            }
            PaletteDivider()
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(EnsoColors.QuickTabOutline, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.palette_footer), style = EnsoType.Secondary)
            }
        }
    }
}

/** Open tabs for the palette: matches for the query, or a few of the space's open tabs before typing. */
internal fun matchingTabs(spaces: SpacesState, query: String): List<Tab> {
    val space = spaces.currentSpace
    if (query.isBlank()) return space.tabs.take(3)
    return (spaces.essentials + space.allTabs)
        .filter { it.title.contains(query.trim(), ignoreCase = true) || it.url.contains(query.trim(), ignoreCase = true) }
        .take(4)
}

@Composable
private fun PaletteField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    focusRequester: FocusRequester,
    onSubmit: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(24.dp), tint = EnsoColors.TextSecondary)
        Spacer(Modifier.width(16.dp))
        val style = EnsoType.Row.copy(fontSize = 18.sp)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(query) }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(stringResource(R.string.address_placeholder), style = style.copy(color = EnsoColors.TextSecondary))
                }
                inner()
            },
        )
    }
}

@Composable
internal fun PaletteRow(
    leading: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(title, style = EnsoType.RowTitle.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@Composable
internal fun CircleIcon(@DrawableRes icon: Int) {
    Box(Modifier.size(26.dp).background(EnsoColors.FieldFill, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = EnsoColors.TextSecondary)
    }
}

@Composable
internal fun SwitchToTabChip() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.palette_switch_to_tab), style = EnsoType.Secondary)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(22.dp).background(EnsoColors.TextSecondary, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, Modifier.size(14.dp), tint = EnsoColors.OnTextPrimary)
        }
    }
}

@Composable
private fun PaletteDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(EnsoColors.TextSecondary.copy(alpha = 0.15f)))
}
