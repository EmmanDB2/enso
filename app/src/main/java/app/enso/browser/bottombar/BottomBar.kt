package app.enso.browser.bottombar

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
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.spacemenu.BlurredPage
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.start.HistoryMatch
import app.enso.browser.toolbar.LoadProgress
import app.enso.browser.toolbar.PageState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon
import app.enso.browser.ui.colors
import app.enso.browser.ui.spaceSurface
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import kotlin.math.roundToInt

/** The cover's panels, each the bottom bar grown upward (boards B2–B4), and the extensions' (step 9). */
enum class BarPanel { QuickTabs, Actions, Settings, Search, Extensions, ExtensionPage }

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

    fun select(tabId: String)

    fun close(tabId: String)

    /** Keeps a Quick Tab in the current space. */
    fun keep(tabId: String)

    /** Keeps a Quick Tab in another space, picked on the space wave. */
    fun keepElsewhere(tabId: String)

    /** Closes every Quick Tab in the current space. */
    fun clearQuickTabs()

    fun back()

    fun forward()

    fun reload()

    fun stop()

    fun copyUrl()

    fun share()

    fun openZenSync()

    /** Opens what was typed, or a history page: a URL, or a web search. */
    fun submit(input: String, mode: SearchMode)

    /** History matching [query], or the latest pages while it's blank. */
    suspend fun history(query: String, limit: Int): List<HistoryMatch>
}

/** The bar at rest (board B1), above the gesture handle: the grab handle's strip, the row and the space below it. */
object BarMetrics {
    val Handle = 20.dp
    val Row = 52.dp
    val Below = 12.dp

    /** Keep in step with `R.dimen.bottom_bar_height`, which the page's scrolling behaviors use. */
    val Rest = Handle + Row + Below
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
    quickTabLifetimeMs: Long,
    thumbnails: ThumbnailStorage,
    extensions: BarExtensions,
    actions: BottomBarActions,
) {
    val space = spaces.currentSpace
    val colors = space.colors()
    val open = panel != null
    val quickTabs = remember(space) { space.tabs.filter { it.quick != null } }
    // The stack shows the latest first: the Quick Tab on screen, then the most recently viewed.
    val stack = remember(quickTabs, selectedTabId) {
        quickTabs.sortedWith(compareByDescending<Tab> { it.id == selectedTabId }.thenByDescending { it.quick?.lastViewedAt })
    }
    val searching = panel == BarPanel.Search
    var query by remember(searching, searchMode) {
        val text = if (searching && searchMode == SearchMode.CurrentTab) page.url else ""
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }

    BackHandler(enabled = open) {
        actions.showPanel(
            when (panel) {
                BarPanel.Settings, BarPanel.Extensions -> BarPanel.Actions
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
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
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
                .padding(bottom = gestureInset),
        ) {
            val swipe = Modifier.verticalSwipe(
                onUp = actions::openTabSheet.takeIf { !open },
                onDown = { actions.showPanel(null) }.takeIf { open },
            )
            Box(swipe.fillMaxWidth().height(BarMetrics.Handle)) {
                LoadProgress(page, colors.accent, Modifier.align(Alignment.TopStart))
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 9.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(EnsoColors.TextSecondary.copy(alpha = 0.35f), CircleShape),
                )
            }
            AnimatedContent(
                targetState = panel,
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
                    BarPanel.QuickTabs -> QuickTabsPanel(
                        tabs = quickTabs,
                        space = space,
                        selectedTabId = selectedTabId,
                        // Up to just below the status bar.
                        height = maxHeight - statusBarInset - 8.dp - BarMetrics.Handle - BarMetrics.Row - BarMetrics.Below - gestureInset,
                        quickTabLifetimeMs = quickTabLifetimeMs,
                        thumbnails = thumbnails,
                        actions = actions,
                    )
                    BarPanel.Actions -> ActionsPanel(page, space, quickTabs.firstOrNull { it.id == selectedTabId }, quickTabLifetimeMs, actions)
                    BarPanel.Settings -> SettingsPanel(actions)
                    BarPanel.Search -> SearchSuggestions(spaces, query.text, searchMode, actions)
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
            if (searching) {
                SearchRow(query, { query = it }, searchMode, colors.accent, actions, swipe)
            } else {
                BarRow(panel, stack, thumbnails, actions, swipe)
            }
            Spacer(Modifier.height(BarMetrics.Below))
        }
    }
}

/** The bar's row: the Quick Tabs stack, "+" (or Clear Quick Tabs over the stack) and the chevron. */
@Composable
private fun BarRow(panel: BarPanel?, stack: List<Tab>, thumbnails: ThumbnailStorage, actions: BottomBarActions, modifier: Modifier) {
    val quickTabsOpen = panel == BarPanel.QuickTabs
    val actionsOpen = panel in setOf(BarPanel.Actions, BarPanel.Settings, BarPanel.Extensions, BarPanel.ExtensionPage)
    Row(
        modifier.fillMaxWidth().height(BarMetrics.Row).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickTabStack(stack, active = quickTabsOpen, thumbnails) {
            actions.showPanel(if (quickTabsOpen) null else BarPanel.QuickTabs)
        }
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            if (quickTabsOpen) {
                Pill(onClick = actions::clearQuickTabs, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_archive), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.bar_clear_quick_tabs), style = EnsoType.RowTitle.copy(fontSize = 17.sp))
                }
            } else {
                Pill(onClick = actions::newSearch, modifier = Modifier.width(148.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_plus),
                        contentDescription = stringResource(R.string.bar_new_search),
                        Modifier.size(26.dp),
                        tint = EnsoColors.TextPrimary,
                    )
                }
            }
        }
        Chevron(down = actionsOpen) { actions.showPanel(if (actionsOpen) null else BarPanel.Actions) }
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

/** The chevron that opens the actions sheet, and closes it as a dark ↓ while it's open (board B3). */
@Composable
private fun Chevron(down: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (down) EnsoColors.TextPrimary else EnsoColors.TextPrimary.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (down) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up),
            contentDescription = stringResource(if (down) R.string.bar_close else R.string.bar_actions),
            Modifier.size(24.dp),
            tint = if (down) Color.White else EnsoColors.TextPrimary,
        )
    }
}

/**
 * The Quick Tabs stack (board B1): the latest two Quick Tabs as small cards with gold edges, and a count badge.
 * Kept tabs never show here. Dimmed when there are none.
 */
@Composable
private fun QuickTabStack(tabs: List<Tab>, active: Boolean, thumbnails: ThumbnailStorage, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (active) Modifier.background(EnsoColors.QuickTabOutlineOnLight.copy(alpha = 0.2f)) else Modifier)
            .clickable(enabled = tabs.isNotEmpty(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (tabs.isEmpty()) 0.35f else 1f
        MiniCard(Modifier.offset((-4).dp, 2.dp).rotate(-10f).alpha(alpha)) {}
        MiniCard(Modifier.offset(2.dp, (-1).dp).rotate(4f).alpha(alpha)) {
            tabs.firstOrNull()?.let { tab ->
                TabThumbnail(tab.id, thumbnails, Modifier.fillMaxSize(), ContentScale.Crop) {
                    LetterFavicon(tab.title, tab.url, size = 18.dp)
                }
            }
        }
        if (tabs.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(22.dp)
                    .background(EnsoColors.QuickTabOutlineOnLight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(tabs.size.toString(), style = EnsoType.Row.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun MiniCard(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .size(width = 28.dp, height = 36.dp)
            .background(CardCream, shape)
            .border(2.dp, EnsoColors.QuickTabOutlineOnLight, shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) { content() }
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

/** A thin line across a panel, above its row. */
@Composable
internal fun BarDivider() {
    Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(EnsoColors.TextSecondary.copy(alpha = 0.2f)))
}

private val Corner = 26.dp
private val Scrim = Color.Black.copy(alpha = 0.22f)

/** The bar's light pills and tiles over the space's tint. */
internal val PillFill = Color.White.copy(alpha = 0.62f)
private val CardCream = Color(0xFFFFF8EA)
