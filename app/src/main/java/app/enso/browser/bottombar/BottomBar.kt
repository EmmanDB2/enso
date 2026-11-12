package app.enso.browser.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.spacemenu.BlurredPage
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.start.HistoryMatch
import app.enso.browser.toolbar.LoadProgress
import app.enso.browser.toolbar.PageState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.colors
import app.enso.browser.ui.spaceSurface
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import kotlin.math.roundToInt

/** The cover's panels, each the bottom bar grown upward (boards B2–B4), and the extensions' (step 9). */
enum class BarPanel { QuickTabs, Actions, SiteSettings, AllSettings, Search, Find, Extensions, ExtensionPage, Downloads, Reader }

/** What bottom search does with what's typed: open it as a new Quick Tab, or load it in the current tab. */
enum class SearchMode { NewQuickTab, CurrentTab }

/** What the bottom bar asks the app to do. */
interface BottomBarActions {
    /** Opens [panel], or closes the open one with null. */
    fun showPanel(panel: BarPanel?)

    /** "+": bottom search, for a new Quick Tab. */
    fun newSearch()

    /** The URL in the actions sheet: bottom search on the current tab's address. */
    fun editUrl()

    fun openTabSheet()

    /** Slides the bar away, as scrolling down does, until a scroll up brings it back. */
    fun hideBar()

    fun select(tabId: String)

    fun close(tabId: String)

    /** Keeps a Quick Tab in the current space. */
    fun keep(tabId: String)

    /** Makes a kept tab of the current space a Quick Tab again (Keep, tapped on a kept tab). */
    fun unkeep(tabId: String)

    /** Keeps a Quick Tab in another space, picked on the space wave. */
    fun keepElsewhere(tabId: String)

    /** Selects [tabId] under the Quick Tabs view, which stays open (it zooms into the tab, or opens the actions sheet). */
    fun focus(tabId: String)

    /** Closes every Quick Tab in the current space, and says how many. */
    fun clearQuickTabs(): Int

    /** Opens the Quick Tabs the last Clear All closed again. */
    fun undoClearQuickTabs()

    fun back()

    fun forward()

    fun reload()

    fun stop()

    fun copyUrl()

    fun share()

    /** Settings in the actions sheet: the site's settings, or All settings when there's no site on screen. */
    fun openSettings()

    /** Reader: shows the page on screen as an article, and closes the sheet. */
    fun showReader()

    /** Find in page: highlights [text]'s matches, or clears them when it's empty. */
    fun find(text: String)

    /** The next (or previous) match of the find. */
    fun findNext(forward: Boolean)

    /** Opens what was typed, or a history page: a URL, or a web search. */
    fun submit(input: String, mode: SearchMode)

    /** History matching [query], or the latest pages while it's blank. */
    suspend fun history(query: String, limit: Int): List<HistoryMatch>

    /** Search suggestions for [query], or none when they're turned off or it looks like an address. */
    suspend fun searchSuggestions(query: String): List<String>

    /** Whether a voice recognizer is installed for the search field's mic. */
    val voiceSearchAvailable: Boolean

    /** The search field's mic: what's said is searched, as if typed and submitted. */
    fun voiceSearch()
}

/**
 * The bar at rest (board B1): the grab handle's strip, the row and the space below it, 90 dp tall with the gesture
 * handle's room. The page's scrolling behaviors slide an anchor of the same [height].
 */
object BarMetrics {
    val Handle = 12.dp
    val Row = 56.dp
    private val Total = 90.dp
    private val MinBelow = 4.dp

    /** The bar's height above the screen's bottom edge, the gesture handle's room included. */
    fun height(gestureInset: Dp): Dp = max(Total, Handle + Row + MinBelow + gestureInset)

    /** The space between the row and the gesture handle's room. */
    fun below(gestureInset: Dp): Dp = height(gestureInset) - Handle - Row - gestureInset
}

/**
 * The cover's bottom bar (board B1), and its panels: the Quick Tabs (B2), the actions sheet (B3) and bottom search
 * (B4). A panel is the same surface growing upward, with its own version of the bar's row pinned at the bottom.
 *
 * At rest the bar slides off the bottom edge as the page scrolls down. Android Components moves an invisible
 * anchor view with the page, and [scrollOffset] reads how far it has gone.
 */
@Composable
fun BottomBar(
    spaces: SpacesState,
    page: PageState,
    selectedTabId: String?,
    panel: BarPanel?,
    searchMode: SearchMode,
    scrollOffset: () -> Float,
    statusBarInset: Dp,
    gestureInset: Dp,
    pageCapture: Pair<ImageBitmap, Rect>?,
    findMatch: Pair<Int, Int>?,
    downloads: BarDownloads,
    reader: BarReader,
    thumbnails: ThumbnailStorage,
    extensions: BarExtensions,
    settings: BarSettings,
    actions: BottomBarActions,
) {
    val space = spaces.currentSpace
    val colors = space.colors()
    // The Quick Tabs view covers the whole screen; the sheet underneath stays at rest.
    val sheet = panel.takeUnless { it == BarPanel.QuickTabs }
    val open = sheet != null
    // How many Quick Tabs Clear All just closed, for its undo toast.
    var cleared by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(cleared) {
        if (cleared != null) {
            delay(5_000)
            cleared = null
        }
    }
    val quickTabs = remember(space) { space.tabs.filter { it.quick != null } }
    // The stack shows the latest first: the Quick Tab on screen, then the most recently viewed.
    val stack = remember(quickTabs, selectedTabId) {
        quickTabs.sortedWith(compareByDescending<Tab> { it.id == selectedTabId }.thenByDescending { it.quick?.lastViewedAt })
    }
    val searching = panel == BarPanel.Search
    // Search and Find have no row: their field sits at the top of the sheet, on the keyboard.
    val typing = searching || panel == BarPanel.Find
    var query by remember(searching, searchMode) {
        val text = if (searching && searchMode == SearchMode.CurrentTab) page.url else ""
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }

    BackHandler(enabled = open) {
        actions.showPanel(
            when (panel) {
                BarPanel.SiteSettings, BarPanel.Extensions -> BarPanel.Actions
                BarPanel.AllSettings -> if (settings.site != null) BarPanel.SiteSettings else BarPanel.Actions
                BarPanel.ExtensionPage -> BarPanel.Extensions
                else -> null
            },
        )
    }
    if (open) BlockSpaceMenu()
    // An extension's page shows through a hole in the bar, as Glance's does: the page is a GeckoView under it.
    var pageHole by remember { mutableStateOf<Rect?>(null) }
    val hole = pageHole.takeIf { panel == BarPanel.ExtensionPage }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                if (hole == null) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRoundRect(Color.Black, hole.topLeft, hole.size, CornerRadius(16.dp.toPx()), blendMode = BlendMode.Clear)
                        }
                },
            ),
    ) {
        val maxHeight = maxHeight
        // The pages behind an extension's page are hidden; their blurred captures stand in under the dim.
        if (panel == BarPanel.ExtensionPage) extensions.backdrop.forEach { (image, bounds) -> BlurredPage(image, bounds) }
        // Find leaves the page undimmed, so its matches show.
        AnimatedVisibility(open && panel != BarPanel.Find, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Scrim)
                    .pointerInput(Unit) { detectTapGestures { actions.showPanel(null) } },
            )
        }
        val shape = RoundedCornerShape(topStart = Corner, topEnd = Corner)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Panels stop below the status bar, which shows while one is open.
                .heightIn(max = maxHeight - statusBarInset - 8.dp)
                .offset { IntOffset(0, if (open) 0 else scrollOffset().roundToInt()) }
                .shadow(18.dp, shape)
                .clip(shape)
                .spaceSurface(colors)
                // Touches on the bar stay on it rather than falling through to the page underneath.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                // A swipe down anywhere on the closed bar hides it; its buttons only take taps.
                .verticalSwipe(onUp = null, onDown = actions::hideBar.takeIf { !open })
                .padding(bottom = if (typing) 0.dp else gestureInset),
        ) {
            val swipe = Modifier.verticalSwipe(
                onUp = actions::openTabSheet.takeIf { !open },
                onDown = if (open) ({ actions.showPanel(null) }) else actions::hideBar,
            )
            Box(swipe.fillMaxWidth().height(BarMetrics.Handle)) {
                LoadProgress(page, colors.accent, Modifier.align(Alignment.TopStart))
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(EnsoColors.TextSecondary.copy(alpha = 0.35f), CircleShape),
                )
            }
            AnimatedContent(
                targetState = sheet,
                transitionSpec = {
                    fadeIn(tween(200, delayMillis = 80)) togetherWith fadeOut(tween(100)) using
                        SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 500f) }
                },
                contentAlignment = Alignment.BottomCenter,
                // At most the room left above the row, so a tall panel (or the keyboard) never pushes the row off.
                modifier = Modifier.weight(1f, fill = false),
                label = "barPanel",
            ) { shownPanel ->
                when (shownPanel) {
                    null -> Box(Modifier.fillMaxWidth())
                    BarPanel.QuickTabs -> Box(Modifier.fillMaxWidth())
                    BarPanel.Actions -> ActionsPanel(page, space, keepState(space, selectedTabId, page), downloads.recent, reader, actions)
                    BarPanel.Reader -> ReaderPanel(reader) { actions.showPanel(BarPanel.Actions) }
                    BarPanel.Downloads -> DownloadsPanel(downloads, maxHeight * 0.6f) { actions.showPanel(BarPanel.Actions) }
                    BarPanel.SiteSettings -> settings.site?.let { site ->
                        SiteSettingsPanel(site, settings.siteActions) { actions.showPanel(BarPanel.Actions) }
                    } ?: Box(Modifier.fillMaxWidth())
                    BarPanel.AllSettings -> AllSettingsPanel(
                        height = maxHeight - statusBarInset - 8.dp - BarMetrics.height(gestureInset),
                        onBack = { actions.showPanel(if (settings.site != null) BarPanel.SiteSettings else BarPanel.Actions) },
                        content = settings.all,
                    )
                    BarPanel.Find -> FindBar(findMatch, colors.accent, actions::find, actions::findNext) { actions.showPanel(null) }
                    BarPanel.Search -> SearchSheet(spaces, stack, query, { query = it }, searchMode, colors.accent, actions)
                    BarPanel.Extensions -> ExtensionsPanel(extensions, maxHeight * 0.6f) { actions.showPanel(BarPanel.Actions) }
                    BarPanel.ExtensionPage -> ExtensionPagePanel(
                        title = extensions.pageTitle.orEmpty(),
                        height = maxHeight * 0.58f,
                        onBack = { actions.showPanel(BarPanel.Extensions) },
                        onPageArea = { area ->
                            pageHole = area
                            extensions.onPageArea(area)
                        },
                    )
                }
            }
            if (!typing) {
                BarRow(sheet, stack, thumbnails, downloads, colors.accent, actions, swipe)
                Spacer(Modifier.height(BarMetrics.below(gestureInset)))
            }
        }
        cleared?.let { count ->
            ClearedToast(count, Modifier.align(Alignment.BottomCenter).padding(bottom = BarMetrics.height(gestureInset) + 12.dp)) {
                actions.undoClearQuickTabs()
                cleared = null
            }
        }
        if (panel == BarPanel.QuickTabs) {
            QuickTabsView(quickTabs, space, selectedTabId, pageCapture, gestureInset, thumbnails, actions) { cleared = it }
        }
    }
}

/** "3 Quick Tabs cleared · Undo", for a few seconds after Clear All. */
@Composable
private fun ClearedToast(count: Int, modifier: Modifier, onUndo: () -> Unit) {
    val style = EnsoType.Row.copy(fontSize = 15.sp, color = EnsoColors.OnTextPrimary)
    Row(
        modifier
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(EnsoColors.TextPrimary)
            .height(44.dp)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.bar_quick_tabs_cleared, count, count) + " ·", style = style)
        Text(
            stringResource(R.string.bar_undo),
            style = style.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(CircleShape).clickable(onClick = onUndo).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** What the Keep tile does for the tab on screen: a tab of [space]'s own list is a Quick Tab or kept; anything else is pinned or an Essential. */
private fun keepState(space: Space, selectedTabId: String?, page: PageState): KeepState {
    if (selectedTabId == null || page.url.isEmpty()) return KeepState.None
    val tab = space.tabs.firstOrNull { it.id == selectedTabId } ?: return KeepState.Fixed
    return if (tab.quick != null) KeepState.Quick(tab.id) else KeepState.Kept(tab.id)
}

/** The bar's row: the Quick Tabs launcher, "+" and the chevron. */
@Composable
private fun BarRow(
    panel: BarPanel?,
    stack: List<Tab>,
    thumbnails: ThumbnailStorage,
    downloads: BarDownloads,
    accent: Color,
    actions: BottomBarActions,
    modifier: Modifier,
) {
    val actionsOpen = panel in setOf(BarPanel.Actions, BarPanel.SiteSettings, BarPanel.AllSettings, BarPanel.Extensions, BarPanel.ExtensionPage, BarPanel.Downloads, BarPanel.Reader)
    Row(
        modifier.fillMaxWidth().height(BarMetrics.Row).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickTabStack(stack, thumbnails) { actions.showPanel(BarPanel.QuickTabs) }
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Pill(onClick = actions::newSearch, modifier = Modifier.width(148.dp)) {
                Icon(
                    painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.bar_new_search),
                    Modifier.size(26.dp),
                    tint = EnsoColors.TextPrimary,
                )
            }
        }
        Chevron(
            down = actionsOpen,
            downloads = downloads,
            accent = accent,
            onClick = { actions.showPanel(if (actionsOpen) null else BarPanel.Actions) },
            onDownloads = { actions.showPanel(BarPanel.Downloads) },
        )
    }
}

@Composable
private fun Pill(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(PillFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The chevron that opens the actions sheet, and closes it as a dark ↓ while it's open (board B3). For a couple of
 * seconds after a download starts it's a download button in the space's accent that opens the Downloads page; then,
 * while the download runs, a ring around it shows how far it has got.
 */
@Composable
private fun Chevron(down: Boolean, downloads: BarDownloads, accent: Color, onClick: () -> Unit, onDownloads: () -> Unit) {
    val flash = downloads.justStarted && !down
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (downloads.running && !flash && !down) DownloadRing(downloads.progress, accent, Modifier.matchParentSize())
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(
                    when {
                        down -> EnsoColors.TextPrimary
                        flash -> accent
                        else -> EnsoColors.TextPrimary.copy(alpha = 0.08f)
                    },
                )
                .clickable(onClick = if (flash) onDownloads else onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = flash, label = "chevronDownload") { showDownload ->
                if (showDownload) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.downloads_title), Modifier.size(24.dp), tint = Color.White)
                } else {
                    Icon(
                        painterResource(if (down) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up),
                        contentDescription = stringResource(if (down) R.string.bar_close else R.string.bar_actions),
                        Modifier.size(24.dp),
                        tint = if (down) EnsoColors.OnTextPrimary else EnsoColors.TextPrimary,
                    )
                }
            }
        }
    }
}

/**
 * The Quick Tabs launcher (board B1): the latest two Quick Tabs' pages as small tilted cards, as Arc Search shows
 * them. The one before sits behind, the current one on top, each with a soft shadow. Kept tabs never show here.
 * Dimmed when there are none.
 */
@Composable
private fun QuickTabStack(tabs: List<Tab>, thumbnails: ThumbnailStorage, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = tabs.isNotEmpty(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (tabs.isEmpty()) 0.35f else 1f
        MiniCard(tabs.getOrNull(1), thumbnails, Modifier.offset((-5).dp, 1.dp).rotate(-9f).alpha(alpha))
        MiniCard(tabs.firstOrNull(), thumbnails, Modifier.offset(4.dp, (-1).dp).rotate(5f).alpha(alpha))
    }
}

/** One of the launcher's cards: [tab]'s page, or its favicon until there's a capture. */
@Composable
private fun MiniCard(tab: Tab?, thumbnails: ThumbnailStorage, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(width = 26.dp, height = 34.dp)
            .shadow(5.dp, shape)
            .background(EnsoColors.QuickTabCard, shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        tab?.let { TabThumbnail(it.id, thumbnails, Modifier.fillMaxSize(), ContentScale.Crop) { Favicon(it, size = 16.dp) } }
    }
}

/** A vertical swipe: [onUp] or [onDown] when it travels far enough. */
private fun Modifier.verticalSwipe(onUp: (() -> Unit)?, onDown: (() -> Unit)?): Modifier = this.then(
    Modifier.pointerInput(onUp != null, onDown != null) {
        if (onUp == null && onDown == null) return@pointerInput
        val threshold = 32.dp.toPx()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onVerticalDrag = { change, amount ->
                change.consume()
                total += amount
            },
            onDragEnd = {
                if (total < -threshold) onUp?.invoke() else if (total > threshold) onDown?.invoke()
            },
        )
    },
)

private val Corner = 26.dp
private val Scrim = Color.Black.copy(alpha = 0.22f)

/** The bar's light pills and tiles over the space's tint. */
internal val PillFill: Color get() = EnsoColors.PillFill
