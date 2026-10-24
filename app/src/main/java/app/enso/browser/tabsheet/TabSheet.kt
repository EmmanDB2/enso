package app.enso.browser.tabsheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import app.enso.browser.spaces.TabEntry
import app.enso.browser.spaces.TabPlace
import app.enso.browser.spaces.find
import app.enso.browser.spaces.space
import app.enso.browser.spaces.toggleFolder
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon
import app.enso.browser.ui.SpaceColors
import app.enso.browser.ui.SpaceIcon
import app.enso.browser.ui.colors
import app.enso.browser.ui.spaceSurface
import app.enso.browser.ui.displayTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.support.ktx.util.URLStringUtils
import kotlin.math.abs

/** What the tab sheet asks the app to do. */
interface TabSheetActions {
    fun select(tabId: String)

    /** Closes an open tab. */
    fun close(tabId: String)

    /** Unloads a pinned tab or Essential. */
    fun unload(tabId: String)

    /** Opens what was typed in the search field as a new tab. */
    fun open(input: String)

    fun edit(transform: (SpacesState) -> SpacesState)

    /** Moves to the next (+1) or previous (-1) space. False when there's none. */
    fun switchSpace(step: Int): Boolean

    /** Opens the space picker for a Quick Tab, which then moves to the chosen space. */
    fun addToAnotherSpace(tabId: String)

    fun dismiss()
}

/** Cover-screen tab sheet (board 4), in the same layout as the inner sidebar will use. */
@Composable
fun TabSheet(
    spaces: SpacesState,
    /** The Essentials this space shows (see [app.enso.browser.spaces.essentialsFor]). */
    essentials: List<Tab>,
    selectedTabId: String?,
    statusBarInset: Dp,
    quickTabLifetimeMs: Long,
    /** Whether swiping the header sideways switches spaces. */
    headerSwitchesSpaces: Boolean,
    actions: TabSheetActions,
) {
    val colors = spaces.currentSpace.colors()
    val accent = colors.accent
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    val current = selectedTabId?.let { spaces.find(it) }
    val rows = remember(spaces, essentials, current, query) { sheetRows(spaces, essentials, current, query) }
    val listState = rememberLazyListState()

    BackHandler(enabled = menu != null) { menu = null }

    // Open anchored on the current tab: scroll it a third of the way down when the list is long.
    LaunchedEffect(Unit) {
        val index = rows.indexOfFirst { it is SheetRow.Current }
        if (index > 0) {
            val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            listState.scrollToItem(index, scrollOffset = -viewport / 3)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .spaceSurface(colors)
            // Keep touches from reaching the page underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(Modifier.fillMaxSize().padding(top = statusBarInset)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    val openMenu = { target: MenuTarget -> menu = target }
                    when (row) {
                        is SheetRow.Header -> SheetHeader(
                            space = row.space,
                            onSwipe = actions::switchSpace.takeIf { headerSwitchesSpaces },
                        )
                        is SheetRow.Essentials -> EssentialsGrid(row.tabs, selectedTabId, actions, openMenu)
                        is SheetRow.PinnedTabRow -> TabRow(
                            tab = row.tab,
                            colors = colors,
                            highlight = if (row.tab.id == selectedTabId) RowHighlight.Soft else RowHighlight.None,
                            depth = row.depth,
                            swipe = SwipeAction.Unload,
                            actions = actions,
                            onLongClick = openMenu,
                        )
                        is SheetRow.FolderRow -> FolderRow(
                            folder = row.folder,
                            depth = row.depth,
                            colors = colors,
                            actions = actions,
                            onLongClick = openMenu,
                        )
                        SheetRow.Divider -> SheetDivider()
                        is SheetRow.Current -> CurrentTabCard(row.entry, actions, openMenu)
                        is SheetRow.OpenTabRow -> TabRow(
                            tab = row.tab,
                            colors = colors,
                            swipe = SwipeAction.Close,
                            actions = actions,
                            onLongClick = openMenu,
                        )
                        is SheetRow.Match -> TabRow(
                            tab = row.tab,
                            colors = colors,
                            swipe = null,
                            actions = actions,
                            onLongClick = null,
                        )
                    }
                }
            }
            SheetSearchField(
                query = query,
                onQueryChange = { query = it },
                accent = accent,
                onSubmit = { if (it.isNotBlank()) actions.open(it.trim()) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        menu?.let { target ->
            TabMenu(
                target = target,
                spaces = spaces,
                selectedTabId = selectedTabId,
                quickTabLifetimeMs = quickTabLifetimeMs,
                actions = actions,
                onDismiss = { menu = null },
            )
        }
    }
}

/** The sheet's rows, top to bottom (spec order 1–6; the search field is 7). */
private sealed interface SheetRow {
    val key: String

    data class Header(val space: Space) : SheetRow {
        override val key get() = "header"
    }

    data class Essentials(val tabs: List<Tab>) : SheetRow {
        override val key get() = "essentials"
    }

    /** [depth] is how many folders deep the row sits. */
    data class PinnedTabRow(val tab: Tab, val depth: Int) : SheetRow {
        override val key get() = tab.id
    }

    data class FolderRow(val folder: Folder, val depth: Int) : SheetRow {
        override val key get() = folder.id
    }

    data object Divider : SheetRow {
        override val key get() = "divider"
    }

    data class Current(val entry: TabEntry) : SheetRow {
        override val key get() = "current"
    }

    data class OpenTabRow(val tab: Tab) : SheetRow {
        override val key get() = tab.id
    }

    data class Match(val tab: Tab) : SheetRow {
        override val key get() = tab.id
    }
}

private fun sheetRows(spaces: SpacesState, essentials: List<Tab>, current: TabEntry?, query: String): List<SheetRow> {
    val space = spaces.currentSpace
    if (query.isNotBlank()) {
        return (essentials + space.allTabs)
            .filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }
            .map(SheetRow::Match)
    }
    return buildList {
        add(SheetRow.Header(space))
        if (essentials.isNotEmpty()) add(SheetRow.Essentials(essentials))
        fun addPinned(items: List<PinnedItem>, depth: Int) {
            for (item in items) {
                when (item) {
                    is PinnedTab -> add(SheetRow.PinnedTabRow(item.tab, depth))
                    is Folder -> {
                        add(SheetRow.FolderRow(item, depth))
                        if (item.expanded) addPinned(item.items, depth + 1)
                    }
                }
            }
        }
        addPinned(space.pinned, depth = 0)
        if (space.pinned.isNotEmpty()) add(SheetRow.Divider)
        // The current tab only gets the card when it belongs to this space (not after switching spaces).
        current?.takeIf { entry -> entry.place.space?.id == space.id || essentials.any { it.id == entry.tab.id } }
            ?.let { add(SheetRow.Current(it)) }
        space.tabs.filterNot { it.id == current?.tab?.id }.forEach { add(SheetRow.OpenTabRow(it)) }
    }
}

/** Space icon, name and tab count. With [onSwipe], swiping it sideways switches spaces. */
@Composable
private fun SheetHeader(space: Space, onSwipe: ((Int) -> Boolean)?) {
    val count = space.allTabs.size
    val haptics = LocalHapticFeedback.current
    val swipe by rememberUpdatedState(onSwipe)
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(onSwipe != null) {
                if (onSwipe == null) return@pointerInput
                val threshold = 48.dp.toPx()
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                    },
                    onDragEnd = {
                        // Swiping left shows the next space, like turning a page.
                        val step = if (dragged < 0) 1 else -1
                        if (abs(dragged) > threshold && swipe?.invoke(step) == true) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                )
            }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpaceIcon(space.icon, size = 26.dp, fallbackName = space.name)
        Spacer(Modifier.width(12.dp))
        Text(space.name, style = EnsoType.SpaceName, modifier = Modifier.weight(1f), maxLines = 1)
        Text(pluralStringResource(R.plurals.tab_count, count, count), style = EnsoType.Secondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EssentialsGrid(
    tabs: List<Tab>,
    selectedTabId: String?,
    actions: TabSheetActions,
    onLongClick: (MenuTarget) -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tab ->
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    val selected = tab.id == selectedTabId
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .onGloballyPositioned { bounds = it.boundsInRoot() }
                            .then(if (selected) Modifier.shadow(4.dp, RoundedCornerShape(12.dp)) else Modifier)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) EnsoColors.CurrentTab else EnsoColors.FieldFill)
                            .combinedClickable(
                                onClick = { actions.select(tab.id) },
                                onLongClick = { onLongClick(MenuTarget.ForTab(tab.id, bounds)) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        LetterFavicon(tab.title, tab.url)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

enum class SwipeAction { Close, Unload }

/** How a tab row stands out: not at all, softly (the sheet's pinned current tab), or as the white current-tab pill. */
enum class RowHighlight { None, Soft, Current }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabRow(
    tab: Tab,
    colors: SpaceColors,
    swipe: SwipeAction?,
    actions: TabSheetActions,
    onLongClick: ((MenuTarget) -> Unit)?,
    depth: Int = 0,
    highlight: RowHighlight = RowHighlight.None,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(12.dp)
    val content = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .then(if (highlight == RowHighlight.Current) Modifier.shadow(4.dp, shape) else Modifier)
                .clip(shape)
                // Rows paint the surface under them, so the red close area doesn't show through while swiping.
                .then(
                    when (highlight) {
                        RowHighlight.Current -> Modifier.background(EnsoColors.CurrentTab)
                        RowHighlight.Soft -> Modifier.spaceSurface(colors).background(SoftHighlight)
                        RowHighlight.None -> Modifier.spaceSurface(colors)
                    },
                )
                .quickTabOutline(tab, shape)
                .combinedClickable(
                    onClick = { actions.select(tab.id) },
                    onLongClick = onLongClick?.let { { it(MenuTarget.ForTab(tab.id, bounds)) } },
                )
                .padding(start = 12.dp + FolderIndent * depth, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterFavicon(tab.title, tab.url)
            Spacer(Modifier.width(14.dp))
            Text(tab.displayTitle(), style = EnsoType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (swipe == null) content() else SwipeToClose(swipe, onSwiped = { actions.swipe(tab.id, swipe) }) { content() }
}

internal fun TabSheetActions.swipe(tabId: String, action: SwipeAction) = when (action) {
    SwipeAction.Close -> close(tabId)
    SwipeAction.Unload -> unload(tabId)
}

/** Swipe a row sideways to close it: a red close area shows behind it. Unloaded rows spring back. */
@Composable
internal fun SwipeToClose(
    action: SwipeAction,
    onSwiped: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            // Only while swiping, so it can't peek around rounded corners at rest.
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val alignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EnsoColors.CloseSwipe)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(
                        if (action == SwipeAction.Close) R.string.action_close_tab else R.string.action_unload_tab,
                    ),
                    tint = Color.White,
                )
            }
        },
        onDismiss = {
            onSwiped()
            if (action == SwipeAction.Unload) scope.launch { state.reset() }
        },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: Folder,
    depth: Int,
    colors: SpaceColors,
    actions: TabSheetActions,
    onLongClick: ((MenuTarget) -> Unit)?,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(12.dp))
            .spaceSurface(colors)
            .combinedClickable(
                onClick = { actions.edit { it.toggleFolder(folder.id) } },
                onLongClick = onLongClick?.let { { it(MenuTarget.ForFolder(folder.id, bounds)) } },
            )
            .padding(start = 12.dp + FolderIndent * depth, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_folder),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.accent,
        )
        Spacer(Modifier.width(14.dp))
        Text(folder.name, style = EnsoType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SheetDivider() {
    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(EnsoColors.TextSecondary.copy(alpha = 0.25f)),
    )
}

/** The current tab as a white card at the divider, with a pin or breadcrumb when it lives above. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrentTabCard(entry: TabEntry, actions: TabSheetActions, onLongClick: (MenuTarget) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val tab = entry.tab
    val place = entry.place
    val title = tab.displayTitle()
    val folder = (place as? TabPlace.Pinned)?.folder
    val card = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(EnsoColors.CurrentTab)
                .quickTabOutline(tab, RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = { actions.dismiss() },
                    onLongClick = { onLongClick(MenuTarget.ForTab(tab.id, bounds)) },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterFavicon(tab.title, tab.url)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (folder != null) stringResource(R.string.breadcrumb_separator, folder.name, title) else title,
                    style = EnsoType.RowTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    URLStringUtils.toDisplayUrl(tab.url).toString(),
                    style = EnsoType.Url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val marker = when {
                place is TabPlace.Essential -> R.drawable.ic_essentials
                place is TabPlace.Pinned && folder == null -> R.drawable.ic_pin
                else -> null
            }
            marker?.let {
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(it), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextSecondary)
            }
        }
    }
    val swipe = if (place is TabPlace.Open) SwipeAction.Close else SwipeAction.Unload
    // The card's list slot stays while the tab in it changes. Keying the swipe state to the tab
    // stops a dismissed state from carrying over and closing each next tab in turn.
    key(tab.id) {
        SwipeToClose(swipe, onSwiped = { actions.swipe(tab.id, swipe) }, Modifier.padding(vertical = 4.dp)) { card() }
    }
}

@Composable
private fun SheetSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(EnsoColors.FieldFill)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextSecondary)
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = EnsoType.Row,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit(query) }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(stringResource(R.string.tab_sheet_search_placeholder), style = EnsoType.Row.copy(color = EnsoColors.TextSecondary))
                }
                inner()
            },
        )
    }
}

/** How much deeper each folder level sits. */
internal val FolderIndent = 32.dp

/** The white wash over the sheet's pinned current tab. */
private val SoftHighlight = Color.White.copy(alpha = 0.55f)

/** Quick Tabs get a 2dp gold outline (spec: Quick Tabs, "Look"). */
internal fun Modifier.quickTabOutline(tab: Tab, shape: Shape): Modifier =
    if (tab.quick != null) border(2.dp, EnsoColors.QuickTabOutlineOnLight, shape) else this
