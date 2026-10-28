package app.enso.browser.sidebar

import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.PinnedItem
import app.enso.browser.spaces.PinnedTab
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.tabsheet.EssentialsGrid
import app.enso.browser.tabsheet.FolderRow
import app.enso.browser.tabsheet.MenuTarget
import app.enso.browser.tabsheet.RowHighlight
import app.enso.browser.tabsheet.SheetDivider
import app.enso.browser.tabsheet.SplitRow
import app.enso.browser.tabsheet.SplitRows
import app.enso.browser.tabsheet.TabDragActions
import app.enso.browser.tabsheet.TabRow
import app.enso.browser.tabsheet.TabSheetActions
import app.enso.browser.tabsheet.dragAfterHold
import app.enso.browser.tabsheet.quickTabOutline
import app.enso.browser.tabsheet.swipeAction
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.FieldButton
import app.enso.browser.toolbar.OverflowMenu
import app.enso.browser.toolbar.PageState
import app.enso.browser.toolbar.UrlEditor
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.SpaceColors
import app.enso.browser.ui.SpaceIcon
import app.enso.browser.ui.colors
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import kotlin.math.abs

/** A tab playing or paused on media, for the sidebar's media card (board 6). */
data class MediaCardState(
    val tabId: String,
    val title: String,
    val url: String,
    val playing: Boolean,
    val muted: Boolean,
    /** What the page lets the card do: skip tracks, or at least seek. */
    val features: MediaSession.Feature,
    val controller: MediaSession.Controller,
)

/** The media card for [tabs]: the tab playing media, or else the one whose media changed last. */
fun mediaCardFor(tabs: List<TabSessionState>): MediaCardState? {
    val withMedia = tabs.filter { it.mediaSessionState.let { media -> media != null && media.playbackState != MediaSession.PlaybackState.STOPPED } }
    val tab = withMedia.firstOrNull { it.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING }
        ?: withMedia.maxByOrNull { it.mediaSessionState?.timestamp ?: 0L }
        ?: return null
    val media = tab.mediaSessionState ?: return null
    return MediaCardState(
        tabId = tab.id,
        title = tab.content.title,
        url = tab.content.url,
        playing = media.playbackState == MediaSession.PlaybackState.PLAYING,
        muted = media.muted,
        features = media.features,
        controller = media.controller,
    )
}

/** What the sidebar and rail ask the app to do, beyond the tab rows they share with the tab sheet. */
interface SidebarActions {
    fun setExpanded(expanded: Boolean)

    fun back()

    fun forward()

    fun reload()

    fun stop()

    fun setEditing(editing: Boolean)

    /** Loads what was typed in the URL field in the current tab. */
    fun submit(input: String)

    fun openZenSync()

    /** The Extensions card (step 9). */
    fun openExtensions()

    /**
     * Mutes or unmutes [media]. Gecko doesn't report its mute state back, so the app keeps its own record of
     * what it set.
     */
    fun setMuted(media: MediaCardState, muted: Boolean)

    /** New Tab: the start screen with the search palette open (board 9c). */
    fun newTab()

    fun switchToSpace(spaceId: String)

    /** Moves to the next (+1) or previous (-1) space. False when there's none. */
    fun switchSpace(step: Int): Boolean

    /** From the rail: opens the sidebar on the URL field, and closes it again when the edit ends. */
    fun searchFromRail()

    /** From the rail: opens the sidebar with [folderId] expanded. */
    fun openFolder(folderId: String)
}

/**
 * The inner screen's full sidebar (board 6), after Zen's: navigation, the URL field, then the space's
 * Essentials, pinned tabs and folders, New Tab and open tabs, a media card, and the spaces along the bottom.
 * It uses the tab sheet's rows, so holds, swipes and folders behave the same. Swiping sideways anywhere but
 * on a row (where a swipe closes the tab) switches spaces, like Zen's trackpad swipe.
 */
@Composable
fun Sidebar(
    spaces: SpacesState,
    essentialsFor: (Space) -> List<Tab>,
    selectedTabId: String?,
    page: PageState,
    editing: Boolean,
    /** The start screen is showing, so New Tab is the highlighted row (board 9c). */
    newTabShowing: Boolean,
    media: MediaCardState?,
    tabActions: TabSheetActions,
    actions: SidebarActions,
    onMenu: (MenuTarget) -> Unit,
    /** Dragging a held tab onto the page, to split them. */
    drag: TabDragActions?,
    /** The sidebar's width, which the user sets by dragging its edge. */
    width: Dp,
    /**
     * Padding on the page's side. The page card's border already leaves [EdgePadding] there, so it's none with
     * the border on, keeping every gap the same.
     */
    endPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = spaces.currentSpace.colors()
    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .switchesSpaces(actions)
            .padding(start = EdgePadding, top = EdgePadding, end = endPadding, bottom = EdgePadding),
    ) {
        SidebarHeader(page, actions)
        UrlField(page, editing, colors.accent, actions)
        Spacer(Modifier.height(6.dp))
        val order = spaces.spaces.map { it.id }
        AnimatedContent(
            targetState = spaces.currentSpaceId,
            transitionSpec = {
                // The next space comes in from the right, like turning a page.
                val forward = order.indexOf(targetState) > order.indexOf(initialState)
                slideInHorizontally { if (forward) it else -it } togetherWith slideOutHorizontally { if (forward) -it else it }
            },
            modifier = Modifier.weight(1f),
            label = "space",
        ) { spaceId ->
            val space = spaces.spaces.firstOrNull { it.id == spaceId } ?: return@AnimatedContent
            SpaceTabs(space, essentialsFor(space), selectedTabId, newTabShowing, tabActions, actions, onMenu, drag)
        }
        media?.let {
            Spacer(Modifier.height(8.dp))
            MediaCard(it, onOpen = { tabActions.select(it.tabId) }, onMute = { muted -> actions.setMuted(it, muted) })
        }
        Spacer(Modifier.height(8.dp))
        SpaceBar(spaces, actions)
    }
}

/** A sideways swipe switches spaces. Rows that close on a swipe take it first, so this only sees the rest. */
private fun Modifier.switchesSpaces(actions: SidebarActions): Modifier = this.then(
    Modifier.pointerInput(actions) {
        val threshold = SwipeThreshold.toPx()
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                dragged += amount
            },
            onDragEnd = {
                // Swiping left shows the next space, like turning a page.
                if (abs(dragged) > threshold) actions.switchSpace(if (dragged < 0) 1 else -1)
            },
        )
    },
)

@Composable
private fun SidebarHeader(page: PageState, actions: SidebarActions) {
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        BarButton(R.drawable.ic_sidebar, R.string.sidebar_toggle) { actions.setExpanded(false) }
        Spacer(Modifier.weight(1f))
        BarButton(R.drawable.ic_arrow_left, R.string.menu_back, enabled = page.canGoBack, onClick = actions::back)
        BarButton(R.drawable.ic_arrow_right, R.string.menu_forward, enabled = page.canGoForward, onClick = actions::forward)
        if (page.loading) {
            BarButton(R.drawable.ic_close, R.string.action_stop, onClick = actions::stop)
        } else {
            BarButton(R.drawable.ic_reload, R.string.action_reload, enabled = page.url.isNotEmpty(), onClick = actions::reload)
        }
    }
}

@Composable
private fun BarButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ButtonSize)) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(label),
            modifier = Modifier.size(24.dp),
            tint = EnsoColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

/**
 * The URL, as in Zen's sidebar: tap to edit (it loads in the current tab), with copy link and the ⋮ menu where
 * Zen has its copy link and site settings buttons.
 */
@Composable
private fun UrlField(page: PageState, editing: Boolean, accent: androidx.compose.ui.graphics.Color, actions: SidebarActions) {
    val context = LocalContext.current
    val host = AddressInput.displayHost(page.url)
    var input by remember(editing) {
        val text = if (host.isEmpty()) "" else page.url
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(EnsoColors.FieldFill)
            .then(if (editing) Modifier else Modifier.clickable { actions.setEditing(true) })
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (editing) {
                UrlEditor(
                    value = input,
                    onValueChange = { input = it },
                    accent = accent,
                    onSubmit = actions::submit,
                    onCancel = { actions.setEditing(false) },
                )
            } else {
                Text(
                    host.ifEmpty { stringResource(R.string.address_placeholder) },
                    style = if (host.isEmpty()) EnsoType.Row.copy(color = EnsoColors.TextSecondary) else EnsoType.Row,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            editing -> if (input.text.isNotEmpty()) FieldButton(R.drawable.ic_close, R.string.action_clear, size = ButtonSize) { input = TextFieldValue() }
            else -> {
                if (page.url.isNotEmpty()) {
                    FieldButton(R.drawable.ic_link, R.string.action_copy_link, size = ButtonSize) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(host, page.url))
                    }
                }
                OverflowMenu(onOpenExtensions = actions::openExtensions, onOpenZenSync = actions::openZenSync, buttonSize = ButtonSize)
            }
        }
    }
}

/** One space's tabs, as in the tab sheet but in place: the current tab is highlighted where it sits. */
@Composable
private fun SpaceTabs(
    space: Space,
    essentials: List<Tab>,
    selectedTabId: String?,
    newTabShowing: Boolean,
    tabActions: TabSheetActions,
    actions: SidebarActions,
    onMenu: (MenuTarget) -> Unit,
    drag: TabDragActions?,
) {
    val rows = SidebarRows(space.colors(), selectedTabId.takeIf { !newTabShowing }, SplitRows(space), tabActions, onMenu, drag)
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (essentials.isNotEmpty()) {
            item(key = "essentials") {
                EssentialsGrid(essentials, rows.current, tabActions, onMenu, tileHeight = RowHeight, gap = EdgePadding, bottomPadding = 4.dp)
            }
        }
        item(key = "name") { SpaceName(space) }
        pinnedRows(space.pinned, depth = 0, rows)
        if (space.pinned.isNotEmpty()) item(key = "divider") { SheetDivider() }
        item(key = "new-tab") { NewTabRow(highlighted = newTabShowing, onClick = actions::newTab) }
        space.tabs.forEach { tab -> tabRow(tab, depth = 0, rows) }
    }
}

/** What every row of one space's list in the sidebar shares. */
private class SidebarRows(
    val colors: SpaceColors,
    /** The tab on screen, unless New Tab is. */
    val current: String?,
    val splits: SplitRows,
    val tabActions: TabSheetActions,
    val onMenu: (MenuTarget) -> Unit,
    val drag: TabDragActions?,
)

private fun androidx.compose.foundation.lazy.LazyListScope.pinnedRows(items: List<PinnedItem>, depth: Int, rows: SidebarRows) {
    for (item in items) {
        when (item) {
            is PinnedTab -> tabRow(item.tab, depth, rows)
            is Folder -> {
                item(key = item.id) { FolderRow(item, depth, rows.colors, rows.tabActions, rows.onMenu, height = RowHeight) }
                if (item.expanded) pinnedRows(item.items, depth + 1, rows)
            }
        }
    }
}

/** A tab's row, or its split's row when the split shows here (see [SplitRows]). */
private fun androidx.compose.foundation.lazy.LazyListScope.tabRow(tab: Tab, depth: Int, rows: SidebarRows) {
    if (rows.splits.isHidden(tab.id)) return
    val split = rows.splits.at(tab.id)
    if (split != null) {
        item(key = "split:" + tab.id) {
            SplitRow(
                tabs = split,
                colors = rows.colors,
                selectedTabId = rows.current,
                actions = rows.tabActions,
                onLongClick = rows.onMenu,
                depth = depth,
                highlight = if (split.any { it.id == rows.current }) RowHighlight.Current else RowHighlight.None,
                height = RowHeight,
                drag = rows.drag,
            )
        }
    } else {
        item(key = tab.id) {
            TabRow(
                tab = tab,
                colors = rows.colors,
                swipe = tab.swipeAction,
                actions = rows.tabActions,
                onLongClick = rows.onMenu,
                depth = depth,
                highlight = if (tab.id == rows.current) RowHighlight.Current else RowHighlight.None,
                height = RowHeight,
                drag = rows.drag,
            )
        }
    }
}

@Composable
private fun SpaceName(space: Space) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SpaceIcon(space.icon, size = 20.dp, tint = EnsoColors.TextSecondary, fallbackName = space.name)
        Spacer(Modifier.width(12.dp))
        Text(space.name, style = EnsoType.RowTitle.copy(color = EnsoColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NewTabRow(highlighted: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .then(if (highlighted) Modifier.shadow(4.dp, shape).background(EnsoColors.CurrentTab, shape) else Modifier)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (highlighted) EnsoColors.TextPrimary else EnsoColors.TextSecondary,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(R.string.sidebar_new_tab),
            style = if (highlighted) EnsoType.RowTitle else EnsoType.Row.copy(color = EnsoColors.TextSecondary),
        )
    }
}

/**
 * The tab playing media, with previous, play or pause, next and mute (board 6). Its favicon opens the tab.
 * Previous and next skip tracks when the page has them, seek back and forward when it only lets you seek, and
 * are grayed out otherwise.
 */
@Composable
private fun MediaCard(media: MediaCardState, onOpen: () -> Unit, onMute: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(4.dp, shape)
            .background(EnsoColors.CurrentTab, shape)
            .clip(shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            Favicon(media.title, media.url, media.tabId)
        }
        Spacer(Modifier.weight(1f))
        val features = media.features
        when {
            features.contains(MediaSession.Feature.PREVIOUS_TRACK) ->
                BarButton(R.drawable.ic_skip_previous, R.string.media_previous) { media.controller.previousTrack() }
            features.contains(MediaSession.Feature.SEEK_BACKWARD) ->
                BarButton(R.drawable.ic_seek_back, R.string.media_seek_back) { media.controller.seekBackward() }
            else -> BarButton(R.drawable.ic_skip_previous, R.string.media_previous, enabled = false) {}
        }
        if (media.playing) {
            BarButton(R.drawable.ic_pause, R.string.media_pause) { media.controller.pause() }
        } else {
            BarButton(R.drawable.ic_play, R.string.media_play) { media.controller.play() }
        }
        when {
            features.contains(MediaSession.Feature.NEXT_TRACK) ->
                BarButton(R.drawable.ic_skip_next, R.string.media_next) { media.controller.nextTrack() }
            features.contains(MediaSession.Feature.SEEK_FORWARD) ->
                BarButton(R.drawable.ic_seek_forward, R.string.media_seek_forward) { media.controller.seekForward() }
            else -> BarButton(R.drawable.ic_skip_next, R.string.media_next, enabled = false) {}
        }
        Spacer(Modifier.weight(1f))
        if (media.muted) {
            BarButton(R.drawable.ic_volume_off, R.string.media_unmute) { onMute(false) }
        } else {
            BarButton(R.drawable.ic_volume, R.string.media_mute) { onMute(true) }
        }
    }
}

/** The spaces along the bottom (board 6), centered when they fit; the current one sits on a tile. */
@Composable
private fun SpaceBar(spaces: SpacesState, actions: SidebarActions) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spaces.spaces.forEach { space ->
                val current = space.id == spaces.currentSpaceId
                Box(
                    Modifier
                        .size(RowHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (current) Modifier.background(EnsoColors.FieldFill) else Modifier)
                        .clickable {
                            if (!current) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                actions.switchToSpace(space.id)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    SpaceIcon(space.icon, size = 22.dp, fallbackName = space.name)
                }
            }
        }
    }
}

/**
 * The collapsed rail (board 5, the default): the toggle, a search button, then the space's Essentials, pinned
 * tabs and folders, and open tabs as favicons, with the current space's icon at the bottom. Swiping it sideways
 * switches spaces.
 */
@Composable
fun Rail(
    spaces: SpacesState,
    essentials: List<Tab>,
    selectedTabId: String?,
    newTabShowing: Boolean,
    tabActions: TabSheetActions,
    actions: SidebarActions,
    onMenu: (MenuTarget) -> Unit,
    drag: TabDragActions?,
    modifier: Modifier = Modifier,
) {
    val space = spaces.currentSpace
    val colors = space.colors()
    val current = selectedTabId.takeIf { !newTabShowing }
    val splits = SplitRows(space)
    // A tab's tile, or its split's stacked tile (board 7) where the split shows.
    val tabTile = @Composable { tab: Tab ->
        val split = splits.at(tab.id)
        when {
            splits.isHidden(tab.id) -> Unit
            split != null -> RailSplit(split, current, tabActions, onMenu, drag)
            else -> RailTab(tab, selected = tab.id == current, onFill = false, tabActions, onMenu, drag)
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(RailWidth)
            .switchesSpaces(actions)
            .padding(start = EdgePadding, top = EdgePadding, bottom = EdgePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BarButton(R.drawable.ic_sidebar, R.string.sidebar_toggle) { actions.setExpanded(true) }
        Spacer(Modifier.height(4.dp))
        RailTile(onClick = actions::searchFromRail, background = EnsoColors.FieldFill) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.action_open_search), tint = EnsoColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Essentials can't be split, so they don't drag.
            essentials.forEach { RailTab(it, selected = it.id == current, onFill = true, tabActions, onMenu, drag = null) }
            if (essentials.isNotEmpty()) RailDivider()
            space.pinned.forEach { item ->
                when (item) {
                    is PinnedTab -> tabTile(item.tab)
                    is Folder -> RailFolder(item, colors.accent, onClick = { actions.openFolder(item.id) }, onMenu)
                }
            }
            if (space.pinned.isNotEmpty()) RailDivider()
            space.tabs.forEach { tabTile(it) }
        }
        Spacer(Modifier.height(8.dp))
        RailTile(onClick = { actions.setExpanded(true) }, background = EnsoColors.FieldFill) {
            SpaceIcon(space.icon, size = 24.dp, fallbackName = space.name)
        }
    }
}

@Composable
private fun RailTile(
    onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(RailTileSize)
            .clip(RoundedCornerShape(12.dp))
            .then(if (background != null) Modifier.background(background) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A split in the rail: its tabs' favicons stacked on one tile (board 7), white while the split is on screen.
 * Each favicon selects, holds and drags its own tab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailSplit(tabs: List<Tab>, current: String?, tabActions: TabSheetActions, onMenu: (MenuTarget) -> Unit, drag: TabDragActions?) {
    val shape = RoundedCornerShape(12.dp)
    val shown = tabs.any { it.id == current }
    Column(
        Modifier
            .width(RailTileSize)
            .then(if (shown) Modifier.shadow(4.dp, shape) else Modifier)
            .clip(shape)
            .then(if (shown) Modifier.background(EnsoColors.CurrentTab) else Modifier.background(EnsoColors.FieldFill.copy(alpha = 0.5f)))
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabs.forEach { tab ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val select by rememberUpdatedState { tabActions.select(tab.id) }
            Box(
                Modifier
                    .size(RailTileSize, RailSplitCell)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .clip(RoundedCornerShape(10.dp))
                    .quickTabOutline(tab, RoundedCornerShape(10.dp))
                    .dragAfterHold(tab.id, drag)
                    .combinedClickable(onClick = { select() }, onLongClick = { onMenu(MenuTarget.ForTab(tab.id, bounds)) }),
                contentAlignment = Alignment.Center,
            ) {
                Favicon(tab)
            }
        }
    }
}

/** A tab in the rail: on a gray tile for Essentials ([onFill]), a white one when it's the current tab. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailTab(
    tab: Tab,
    selected: Boolean,
    onFill: Boolean,
    tabActions: TabSheetActions,
    onMenu: (MenuTarget) -> Unit,
    drag: TabDragActions?,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(12.dp)
    val select by rememberUpdatedState { tabActions.select(tab.id) }
    Box(
        Modifier
            .size(RailTileSize)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .then(if (selected) Modifier.shadow(4.dp, shape) else Modifier)
            .clip(shape)
            .then(
                when {
                    selected -> Modifier.background(EnsoColors.CurrentTab)
                    onFill -> Modifier.background(EnsoColors.FieldFill)
                    else -> Modifier
                },
            )
            .quickTabOutline(tab, shape)
            .dragAfterHold(tab.id, drag)
            .combinedClickable(onClick = { select() }, onLongClick = { onMenu(MenuTarget.ForTab(tab.id, bounds)) }),
        contentAlignment = Alignment.Center,
    ) {
        Favicon(tab)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailFolder(folder: Folder, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit, onMenu: (MenuTarget) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier
            .size(RailTileSize)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = { onMenu(MenuTarget.ForFolder(folder.id, bounds)) }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_folder), contentDescription = folder.name, modifier = Modifier.size(24.dp), tint = accent)
    }
}

@Composable
private fun RailDivider() {
    Box(Modifier.width(32.dp).height(1.dp).background(EnsoColors.TextSecondary.copy(alpha = 0.25f)))
}

/**
 * The sidebar's draggable edge: a strip over its right edge and the [gap] beside it, at [edgeX]. Dragging it
 * sideways resizes the sidebar ([onDrag] gets the change in dp); a small grabber shows meanwhile.
 */
@Composable
fun SidebarResizeHandle(edgeX: Dp, gap: Dp, onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    // The handle moves with the edge it resizes, so the finger is tracked in window coordinates: measured
    // against the handle, each resize would cancel out part of the next move.
    var handleX by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .offset(x = edgeX - HandleReach)
            .width(HandleReach + gap)
            .fillMaxHeight()
            .onGloballyPositioned { handleX = it.positionInWindow().x }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // From where the finger landed, so the edge keeps the same distance from it the whole way.
                    var lastX = handleX + down.position.x
                    val follow = { change: PointerInputChange ->
                        change.consume()
                        val x = handleX + change.position.x
                        drag((x - lastX).toDp().value)
                        lastX = x
                    }
                    val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    dragging = true
                    start()
                    follow(first)
                    horizontalDrag(first.id, follow)
                    dragging = false
                    end()
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (dragging) {
            Box(
                Modifier
                    // Centered in the border's gap, or against the sidebar's edge when the border is off.
                    .padding(end = ((gap - Grabber) / 2).coerceAtLeast(0.dp))
                    .width(Grabber)
                    .height(36.dp)
                    .background(EnsoColors.TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** The rail's width, its few dp of padding on the screen side included. */
val RailWidth: Dp = 52.dp

/** The sidebar's narrowest and widest, when dragging its edge. */
val SidebarMinWidth: Dp = 220.dp
val SidebarMaxWidth: Dp = 480.dp

/** The sidebar's and rail's padding from the screen's edge, matching the page card's thin border. */
val EdgePadding: Dp = 4.dp

/** Rows, fields and tiles: thinner than the cover sheet's 48 dp, so more fits. */
private val RowHeight = 40.dp

/** How far into the sidebar its draggable edge reaches, and the grabber's width. */
private val HandleReach = 10.dp
private val Grabber = 3.dp
private val ButtonSize = 36.dp
private val RailTileSize = 40.dp

/** Each favicon's height in a split's stacked rail tile. */
private val RailSplitCell = 36.dp
private val SwipeThreshold = 48.dp
