package app.enso.browser

import android.content.Intent
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.enso.browser.sidebar.CardCorner
import app.enso.browser.sidebar.InnerFrame
import app.enso.browser.sidebar.MediaCardState
import app.enso.browser.sidebar.PageCard
import app.enso.browser.sidebar.Rail
import app.enso.browser.sidebar.RailWidth
import app.enso.browser.sidebar.Sidebar
import app.enso.browser.sidebar.SidebarActions
import app.enso.browser.sidebar.SidebarResizeHandle
import app.enso.browser.sidebar.EdgePadding
import app.enso.browser.sidebar.SidebarMaxWidth
import app.enso.browser.sidebar.SidebarMinWidth
import app.enso.browser.sidebar.mediaCardFor
import app.enso.browser.spacemenu.LocalSpaceMenuBlocks
import app.enso.browser.spacemenu.SpaceMenuBlocks
import app.enso.browser.spacemenu.SpaceMenuGesture
import app.enso.browser.spacemenu.SpaceMenuZones
import app.enso.browser.spacemenu.SpaceWave
import app.enso.browser.spacemenu.SpaceWaveState
import app.enso.browser.spacemenu.WaveSpace
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Split
import app.enso.browser.spaces.TabPlace
import app.enso.browser.spaces.essentialsFor
import app.enso.browser.spaces.find
import app.enso.browser.spaces.isInCurrentSpace
import app.enso.browser.spaces.keepQuickTabIn
import app.enso.browser.spaces.resizeSplit
import app.enso.browser.spaces.select
import app.enso.browser.spaces.space
import app.enso.browser.spaces.splitOf
import app.enso.browser.spaces.splitWith
import app.enso.browser.spaces.toggleFolder
import app.enso.browser.split.DraggedTab
import app.enso.browser.split.PagePanes
import app.enso.browser.split.SplitDivider
import app.enso.browser.split.SplitDividerReach
import app.enso.browser.split.SplitDropZone
import app.enso.browser.split.TabDrag
import app.enso.browser.split.dragDivider
import app.enso.browser.split.paneRects
import app.enso.browser.split.splitDropZone
import app.enso.browser.split.splitShares
import app.enso.browser.start.HistoryMatch
import app.enso.browser.start.StartScreen
import app.enso.browser.start.StartScreenActions
import app.enso.browser.sync.MozillaAccount
import app.enso.browser.sync.ZenSyncSheet
import app.enso.browser.sync.ZenSyncStatus
import app.enso.browser.tabsheet.MenuTarget
import app.enso.browser.tabsheet.TabDragActions
import app.enso.browser.tabsheet.TabMenu
import app.enso.browser.tabsheet.TabSheet
import app.enso.browser.tabsheet.TabSheetActions
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.TopBar
import app.enso.browser.toolbar.TopBarState
import app.enso.browser.toolbar.TopBarView
import app.enso.browser.toolbar.toTopBarState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.colors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.accounts.FxaWebChannelFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.widgets.behavior.DependencyGravity
import mozilla.components.ui.widgets.behavior.EngineViewClippingBehavior
import mozilla.components.ui.widgets.behavior.EngineViewScrollingDataBehavior
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The browser. Folded, the cover's tinted top bar sits over the page (boards 1–4); unfolded, the sidebar or rail
 * sits beside the page as a rounded card (boards 5 and 6). The start screen, tab sheet and menus go over them.
 * Folding keeps the page, tab, space and scroll position: the activity handles the change itself, so only the
 * layout around the page changes.
 */
class MainActivity : ComponentActivity() {
    private val components get() = (application as EnsoApplication).components

    private lateinit var root: CoordinatorLayout

    /** The first page view: the cover's page, and a split's first page. */
    private lateinit var engineView: EngineView
    private lateinit var engineContainer: FrameLayout
    private lateinit var pagePanes: PagePanes
    private lateinit var topBar: TopBarView
    private lateinit var topEdge: View
    private lateinit var windowFeature: WindowFeature
    private lateinit var webChannelFeature: FxaWebChannelFeature

    private var editing by mutableStateOf(false)
    private var tabSheetOpen by mutableStateOf(false)
    private var selectedTabId by mutableStateOf<String?>(null)

    /** Set when the app comes back after being away a while; cleared by opening or switching to a tab. */
    private var startScreenRequested by mutableStateOf(false)

    /** Shown when no tab is selected, the selected tab belongs to another space, or after time away. */
    private val startScreenVisible: Boolean
        get() {
            val selected = selectedTabId ?: return true
            return startScreenRequested ||
                !components.spaces.state.value.isInCurrentSpace(selected, components.settings.essentialsPerContainer)
        }

    private var zenSyncSheetOpen by mutableStateOf(false)

    /** Unfolded: the sidebar or rail beside the page card instead of the cover's top bar. */
    private var inner by mutableStateOf(false)
    private var sidebarExpanded by mutableStateOf(false)

    /** The full sidebar's width in dp, set by dragging its edge. */
    private var sidebarWidthDp by mutableFloatStateOf(0f)

    /** That width as it fits this window: never changes [sidebarWidthDp], so a narrow moment can't shrink it. */
    private var sidebarFullDp by mutableFloatStateOf(0f)

    /** The sidebar's width as drawn, in dp: it animates between the rail and the sidebar, and follows a drag. */
    private var sidebarShownDp by mutableFloatStateOf(0f)
    private var sidebarAnimator: ValueAnimator? = null

    /**
     * While the sidebar moves (opening, closing or being dragged), the page stays put under it at this width
     * (in dp) of sidebar, and resizes once when it stops. Resizing GeckoView every frame would make it reflow.
     */
    private var pinnedPageDp: Float? = null

    /** The rail's search button opened the sidebar, so it closes again when the edit ends. */
    private var collapseAfterEdit = false

    /** The page card on the inner screen, in window pixels. A split shares it out between its pages. */
    private var cardRect by mutableStateOf(Rect.Zero)

    /** The pages on screen, left to right, and their split while one shows (board 7). */
    private var shown by mutableStateOf(Shown(emptyList(), split = null))

    /** Where each page view sits on the inner screen, in window pixels. */
    private var paneViewRects by mutableStateOf(emptyList<Rect>())

    /** While a split's divider is dragged, the pages' shares of the width as drawn; they resize when it stops. */
    private var dividerSizes by mutableStateOf<List<Float>?>(null)

    /** A tab dragged out of the sidebar or rail, to split it with the page. */
    private var tabDrag by mutableStateOf<TabDrag?>(null)

    /** Every tab in a split, which keeps mobile sites unless [Settings.desktopSitesInSplit] is on. */
    private var splitTabIds = emptySet<String>()

    /**
     * The system bars around the inner screen: the cutout on the sides, the status bar on top while it shows,
     * and the navigation bar below when it has buttons (gesture navigation's handle sits over the page).
     */
    private var barInsets by mutableStateOf(Insets.NONE)

    /** The start screen's search palette. New Tab in the sidebar opens it too (board 9c). */
    private var paletteOpen by mutableStateOf(false)

    /** The hold menu opened from the sidebar or rail. */
    private var sidebarMenu by mutableStateOf<MenuTarget?>(null)

    /**
     * Whether Ensō muted each tab's media, with the media session it muted: Gecko doesn't report mute changes,
     * and a new session on the tab starts unmuted.
     */
    private val mutedMedia = mutableStateMapOf<String, Pair<MediaSession.Controller, Boolean>>()

    /** The space menu (board 3) and its picker, drawn over everything in [spaceWaveView]. */
    private val spaceWave = SpaceWaveState()
    private lateinit var spaceWaveView: ComposeView
    private lateinit var spaceMenuGesture: SpaceMenuGesture

    /** Menus and the palette register here while they're up, so a hold doesn't open the space menu over them. */
    private val spaceMenuBlocks = SpaceMenuBlocks()

    /** The address field inside the top bar, where a hold also opens the space menu. */
    private var addressFieldBounds: Rect? = null

    /** The sign-in page's tab, closed once the account is signed in. */
    private var signInTabId: String? = null

    private var statusBarInset by mutableIntStateOf(0)

    /**
     * On the cover, the gesture-navigation handle the page runs under (0 with a bar of buttons, or while the
     * keyboard is up). The tab sheet keeps its search field above it.
     */
    private var gestureHandleInset by mutableIntStateOf(0)
    private var barHeight = 0
    private var barCollapsed = false
    private var canGoBack = false
    private var imeWasVisible = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (editing) editing = false else components.sessionUseCases.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        inner = isInnerScreen(resources.configuration)
        // Before any tab gets an engine session, so the restored ones start in the right mode.
        updateDesktopSites()
        // With nothing to restore, the start screen shows.
        if (components.store.state.selectedTab == null) components.tabs.restore()
        selectedTabId = components.store.state.selectedTabId
        sidebarExpanded = components.settings.innerSidebarExpanded
        sidebarWidthDp = components.settings.innerSidebarWidthDp
        sidebarFullDp = sidebarWidthDp
        sidebarShownDp = if (sidebarExpanded) sidebarWidthDp else RailWidth.value

        engineView = components.engine.createView(this)
        engineView.setActivityContext(this)
        engineContainer = FrameLayout(this).apply {
            setBackgroundColor(EnsoColors.Page.toArgb())
            addView(engineView.asView(), MATCH_PARENT, MATCH_PARENT)
        }
        pagePanes = PagePanes(
            activity = this,
            store = components.store,
            main = engineView,
            mainContainer = engineContainer,
            createView = { components.engine.createView(this).also { it.setActivityContext(this) } },
            // Under the top bar and the overlay, like the first page.
            addContainer = { root.addView(it, root.indexOfChild(engineContainer) + 1) },
            load = { tabId -> if (tabId in shown.tabIds) components.tabs.load(listOf(tabId)) },
        )
        topBar = TopBarView(this).apply {
            isVisible = !inner
            setContent { TopBarContent() }
        }
        topEdge = View(this).apply {
            isVisible = false
            contentDescription = getString(R.string.action_show_top_bar)
            setOnClickListener { topBar.expand() }
        }
        val overlay = ComposeView(this).apply {
            setContent { CompositionLocalProvider(LocalSpaceMenuBlocks provides spaceMenuBlocks) { OverlayContent() } }
        }
        val immersive = components.settings.hideStatusBarWithTopBar
        root = CoordinatorLayout(this).apply {
            val accent = ComposeColor(components.spaces.state.value.currentSpace.accent)
            val chrome = if (immersive) EnsoColors.Page else EnsoColors.spaceTint(accent)
            setBackgroundColor(chrome.toArgb())
            addView(engineContainer)
            addView(topBar)
            addView(topEdge)
            addView(overlay, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
        // Beside the root rather than in it, so the tint covers the system bars too.
        spaceWaveView = ComposeView(this).apply {
            isVisible = false
            setContent { SpaceWave(spaceWave, onPick = ::addToSpace) }
        }
        addContentView(spaceWaveView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        spaceMenuGesture = SpaceMenuGesture(
            host = root,
            holdMs = { components.settings.spaceMenuHoldMs },
            opensAt = ::spaceMenuOpensAt,
            dispatchToViews = { window.superDispatchTouchEvent(it) },
            listener = spaceMenuListener,
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            val edges = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            // Gesture navigation's handle is a thin strip the page can sit under, as in most apps; a bar of
            // buttons keeps its own space.
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val gestureNav = navBottom <= GESTURE_NAV_MAX_DP * resources.displayMetrics.density
            barInsets = Insets.of(
                bars.left,
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                bars.right,
                if (gestureNav) 0 else navBottom,
            )
            if (inner) {
                // The frame and sidebar draw under the system bars themselves; only the keyboard moves things up.
                view.updatePadding(left = 0, top = 0, right = 0, bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                layoutInner()
            } else {
                // With gesture navigation the page runs under the handle; the keyboard still pushes it up.
                val keyboardOrCutout = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout()).bottom
                val bottom = if (gestureNav) keyboardOrCutout else edges.bottom
                gestureHandleInset = (navBottom - bottom).coerceAtLeast(0).takeIf { gestureNav } ?: 0
                view.updatePadding(
                    left = edges.left,
                    top = if (immersive) 0 else statusTop,
                    right = edges.right,
                    bottom = bottom,
                )
                layoutTopBar(statusBarInset = if (immersive) statusTop else 0)
            }
            // Back with the keyboard up only closes the keyboard; treat that as cancelling the edit too.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) editing = false
            imeWasVisible = imeVisible
            WindowInsetsCompat.CONSUMED
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Rotating the unfolded screen resizes the window without changing the layout, and the insets
            // arrive before the new size does, so the page card is laid out again once the size is in.
            val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (inner && resized) root.post { if (inner) layoutInner() }
        }
        root.viewTreeObserver.addOnPreDrawListener {
            val collapsed = barHeight > 0 && topBar.translationY <= -barHeight + 1f
            if (collapsed != barCollapsed) {
                barCollapsed = collapsed
                onTopBarCollapsedChanged(collapsed)
            }
            true
        }

        // Links that open a new window (target=_blank, window.open) become tabs.
        windowFeature = WindowFeature(components.store, components.tabsUseCases)
        // Mozilla's sign-in page talks to the account manager over a web channel.
        webChannelFeature = FxaWebChannelFeature(
            customTabSessionId = null,
            runtime = components.engine,
            store = components.store,
            accountManager = components.accountManager,
            serverConfig = MozillaAccount.serverConfig,
        )
        onBackPressedDispatcher.addCallback(this, backCallback)
        observeState()
        handleDebugIntent(intent)
    }

    /**
     * Folding or unfolding: the page, its tab, the space and the scroll position stay as they are. Only the
     * layout around the page changes, and anything half-done on the old screen (an edit, the tab sheet) closes.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowInner = isInnerScreen(newConfig)
        if (nowInner == inner) return
        inner = nowInner
        updateDesktopSites()
        editing = false
        tabSheetOpen = false
        sidebarMenu = null
        spaceMenuGesture.reset()
        spaceWave.close()
        topBar.isVisible = !inner
        // Make the bar lay itself out again when it comes back.
        if (!inner) barHeight = 0
        ViewCompat.requestApplyInsets(root)
        updateStatusBar()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) focusPageAt(event.x, event.y)
        return spaceMenuGesture.onTouchEvent(event) || super.dispatchTouchEvent(event)
    }

    /**
     * In a split, touching a page that isn't focused focuses it, and the touch goes on to the page as usual.
     * GeckoView keeps touches from its parents, so this watches them on their way in. The divider's touch area
     * reaches into the pages, and touches there are the divider's.
     */
    private fun focusPageAt(x: Float, y: Float) {
        shown.split ?: return
        if (startScreenVisible || sidebarMenu != null || zenSyncSheetOpen || tabDrag != null || dividerSizes != null) return
        val cards = pageCards().map { it.card }
        val reach = SplitDividerReach.value * resources.displayMetrics.density
        if (cards.zipWithNext().any { (left, right) -> x >= left.right - reach && x <= right.left + reach }) return
        val index = cards.indexOfFirst { it.contains(Offset(x, y)) }
        val tabId = shown.tabIds.getOrNull(index) ?: return
        if (tabId == selectedTabId) return
        editing = false
        components.tabs.select(tabId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only, through `adb shell am start -n app.enso.browser/.MainActivity` (plus
     * `--activity-single-top` while the app runs, so the intent reaches `onNewIntent`) with:
     * - `--ez enso_restore_zen_snapshot true`: puts Zen's spaces collection back as it was before the phone's
     *   first write.
     * - `--es enso_debug_open <url>`: opens the URL in a Quick Tab, for test pages.
     * - `--ez enso_debug_desktop_sites true|false`: switches desktop sites on or off whatever the screen, until
     *   the next fold or unfold.
     */
    private fun handleDebugIntent(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent == null) return
        if (intent.getBooleanExtra(EXTRA_RESTORE_ZEN_SNAPSHOT, false)) {
            intent.removeExtra(EXTRA_RESTORE_ZEN_SNAPSHOT)
            components.zenSync.restoreSnapshot()
        }
        intent.getStringExtra(EXTRA_DEBUG_OPEN)?.let { url ->
            intent.removeExtra(EXTRA_DEBUG_OPEN)
            components.tabs.openNewTab(url, quick = true)
            startScreenRequested = false
        }
        if (intent.hasExtra(EXTRA_DEBUG_DESKTOP_SITES)) {
            val desktop = intent.getBooleanExtra(EXTRA_DEBUG_DESKTOP_SITES, false)
            intent.removeExtra(EXTRA_DEBUG_DESKTOP_SITES)
            components.desktopSites.setActive(desktop, components.store)
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = components.settings
        // Anything that expired while we were away goes, even the tab that was on screen.
        components.tabs.clearExpiredQuickTabs(settings.quickTabLifetimeMs, keep = emptyList())
        val away = settings.lastStoppedAt?.let { System.currentTimeMillis() - it }
        if (away != null && away >= settings.startScreenAfterMs) startScreenRequested = true
        windowFeature.start()
        webChannelFeature.start()
        components.zenSync.syncNow(minIntervalMs = ZEN_SYNC_MIN_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        windowFeature.stop()
        webChannelFeature.stop()
        spaceMenuGesture.reset()
        spaceWave.close()
        if (!startScreenVisible) components.tabs.markViewed(shown.tabIds)
        components.settings.lastStoppedAt = System.currentTimeMillis()
        components.spaces.flush()
    }

    override fun onDestroy() {
        pagePanes.release()
        super.onDestroy()
    }

    @Composable
    private fun TopBarContent() {
        val state by components.store.observeAsComposableState { it.selectedTab?.toTopBarState() ?: TopBarState() }
        val spaces by components.spaces.state.collectAsState()
        val useCases = components.sessionUseCases
        TopBar(
            state = state,
            colors = spaces.currentSpace.colors(),
            // The sidebar edits the URL on the inner screen, so the hidden bar doesn't take the focus.
            editing = editing && !inner,
            statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() },
            onEditingChange = { editing = it },
            onSubmit = ::submit,
            onReload = { useCases.reload() },
            onStop = { useCases.stopLoading() },
            onBack = { useCases.goBack() },
            onForward = { useCases.goForward() },
            onOpenTabSheet = { tabSheetOpen = true },
            onSwitchTab = { step -> components.tabs.switchBy(step, components.settings.tabSwipeScope) },
            onOpenZenSync = { zenSyncSheetOpen = true },
            onAddressFieldPlaced = { addressFieldBounds = it },
        )
    }

    /**
     * Everything drawn over the page: on the cover, the start screen and the tab sheet; unfolded, the frame and
     * sidebar around the page card, with the start screen inside the card. The sync sheet goes over both.
     */
    @Composable
    private fun OverlayContent() {
        val spaces by components.spaces.state.collectAsState()
        val zenStatus by components.zenSync.status.collectAsState()
        val statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() }
        if (inner) {
            // A plain container, so the rail and sidebar keep their own widths instead of the window's.
            Box(Modifier.fillMaxSize()) { InnerContent(spaces) }
        } else if (startScreenVisible) {
            // Back from the start screen returns to the last tab, if there is one.
            BackHandler(enabled = selectedTabId != null) { startScreenRequested = false }
            StartScreen(spaces, statusBarInset, paletteOpen, { paletteOpen = it }, startScreenActions)
        }
        AnimatedVisibility(
            visible = tabSheetOpen && !inner,
            enter = fadeIn() + slideInVertically { -it / 12 },
            exit = fadeOut() + slideOutVertically { -it / 12 },
        ) {
            BackHandler { tabSheetOpen = false }
            TabSheet(
                spaces = spaces,
                essentials = spaces.essentialsFor(perContainer = components.settings.essentialsPerContainer),
                selectedTabId = selectedTabId,
                statusBarInset = statusBarInset,
                navigationBarInset = with(LocalDensity.current) { gestureHandleInset.toDp() },
                quickTabLifetimeMs = components.settings.quickTabLifetimeMs,
                headerSwitchesSpaces = components.settings.switchSpacesFromSheetHeader,
                actions = tabSheetActions,
            )
        }
        if (zenSyncSheetOpen) {
            BackHandler { zenSyncSheetOpen = false }
            ZenSyncSheet(
                status = zenStatus,
                accent = ComposeColor(spaces.currentSpace.accent),
                onSignIn = ::signIn,
                onSyncNow = { components.zenSync.syncNow() },
                onSignOut = { components.zenSync.signOut() },
                onDismiss = { zenSyncSheetOpen = false },
            )
        }
    }

    /** The inner screen (boards 5, 6 and 9c): the frame, the sidebar or rail, and the start screen inside the card. */
    @Composable
    private fun InnerContent(spaces: SpacesState) {
        val page by components.store.observeAsComposableState { it.selectedTab?.toTopBarState() ?: TopBarState() }
        val playing by components.store.observeAsComposableState { mediaCardFor(it.tabs) }
        val media = playing?.let { card ->
            mutedMedia[card.tabId]?.takeIf { (controller, _) -> controller === card.controller }?.let { card.copy(muted = it.second) } ?: card
        }
        val density = LocalDensity.current
        val perContainer = components.settings.essentialsPerContainer
        val border = components.settings.innerPageBorder
        val corner = if (border) CardCorner else 0.dp
        val cards = pageCards()
        InnerFrame(spaces.currentSpace.colors(), cards, focused = shown.tabIds.indexOf(selectedTabId).coerceAtLeast(0), page, corner)
        // Opening or closing, the sidebar is drawn at its full width and revealed as its edge moves, cross-fading
        // with the rail, so its rows don't lay out again on every frame.
        val rail = RailWidth.value
        val reveal = ((sidebarShownDp - rail) / (sidebarFullDp - rail)).coerceIn(0f, 1f)
        Box(
            with(density) { Modifier.padding(start = barInsets.left.toDp(), top = barInsets.top.toDp(), bottom = barInsets.bottom.toDp()) }
                .width(sidebarShownDp.dp)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            if (reveal > 0f) {
                Sidebar(
                    spaces = spaces,
                    essentialsFor = { space -> spaces.essentialsFor(space, perContainer) },
                    selectedTabId = selectedTabId,
                    page = page,
                    editing = editing,
                    newTabShowing = startScreenVisible,
                    media = media,
                    tabActions = tabSheetActions,
                    actions = sidebarActions,
                    onMenu = { sidebarMenu = it },
                    drag = tabDragActions,
                    width = maxOf(sidebarFullDp, sidebarShownDp).dp,
                    endPadding = if (border) 0.dp else EdgePadding,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { alpha = reveal },
                )
            }
            if (reveal < 1f) {
                Rail(
                    spaces = spaces,
                    essentials = spaces.essentialsFor(perContainer = perContainer),
                    selectedTabId = selectedTabId,
                    newTabShowing = startScreenVisible,
                    tabActions = tabSheetActions,
                    actions = sidebarActions,
                    onMenu = { sidebarMenu = it },
                    drag = tabDragActions,
                    modifier = Modifier.graphicsLayer { alpha = 1f - reveal },
                )
            }
        }
        if (shown.split != null) {
            cards.zipWithNext().forEachIndexed { index, (left, right) ->
                SplitDivider(
                    gapLeft = left.card.right,
                    gapRight = right.card.left,
                    top = left.card.top,
                    bottom = left.card.bottom,
                    onDragStart = ::startDividerDrag,
                    onDrag = { moveDivider(index, it) },
                    onDragEnd = ::endDividerDrag,
                    onReset = ::resetSplitSizes,
                )
            }
        }
        if (sidebarExpanded && reveal == 1f) {
            SidebarResizeHandle(
                edgeX = with(density) { barInsets.left.toDp() } + sidebarShownDp.dp,
                gap = if (border) BORDER_DP.dp else 0.dp,
                onDragStart = ::startSidebarResize,
                onDrag = ::resizeSidebar,
                onDragEnd = ::endSidebarResize,
            )
        }
        if (startScreenVisible && !cardRect.isEmpty) {
            BackHandler(enabled = selectedTabId != null) { startScreenRequested = false }
            Box(
                Modifier
                    .offset { IntOffset(cardRect.left.roundToInt(), cardRect.top.roundToInt()) }
                    .size(with(density) { cardRect.width.toDp() }, with(density) { cardRect.height.toDp() })
                    .clip(RoundedCornerShape(CardCorner)),
            ) {
                StartScreen(spaces, 0.dp, paletteOpen, { paletteOpen = it }, startScreenActions, centered = true)
            }
        }
        sidebarMenu?.let { target ->
            BackHandler { sidebarMenu = null }
            TabMenu(
                target = target,
                spaces = spaces,
                selectedTabId = selectedTabId,
                quickTabLifetimeMs = components.settings.quickTabLifetimeMs,
                actions = tabSheetActions,
                onDismiss = { sidebarMenu = null },
            )
        }
        tabDrag?.let { drag ->
            if (canDropToSplit(drag.tabId)) {
                val zone = splitDropZone(cardRect, splitGap())
                SplitDropZone(zone, hovered = zone.contains(drag.finger), accent = spaces.currentSpace.colors().accent, corner = corner)
            }
            spaces.find(drag.tabId)?.tab?.let { DraggedTab(it, drag.finger) }
        }
    }

    /** The page cards as drawn, with where their pages are: one card, or a split's pages side by side. */
    private fun pageCards(): List<PageCard> {
        val count = shown.tabIds.size.coerceAtLeast(1)
        val drawn = paneRects(cardRect, count, dividerSizes ?: shown.split?.sizes.orEmpty(), splitGap())
        return drawn.mapIndexed { index, card -> PageCard(card, paneViewRects.getOrNull(index) ?: card) }
    }

    /** The gap between a split's pages, the same as the page card's border (even with the border off). */
    private fun splitGap() = BORDER_DP * resources.displayMetrics.density

    private fun startDividerDrag() {
        val split = shown.split ?: return
        dividerSizes = splitShares(split.sizes, split.tabIds.size)
    }

    /** Moves the divider after page [index] by [delta] pixels, as drawn; the pages resize when the drag ends. */
    private fun moveDivider(index: Int, delta: Float) {
        val split = shown.split ?: return
        val count = split.tabIds.size
        val sizes = dividerSizes ?: return
        val width = cardRect.width - splitGap() * (count - 1)
        val min = SPLIT_PAGE_MIN_DP * resources.displayMetrics.density
        dividerSizes = dragDivider(sizes, count, index, delta, width, min)
    }

    private fun endDividerDrag() {
        val sizes = dividerSizes ?: return
        setSplitSizes(sizes)
        dividerSizes = null
    }

    /** Holding the divider: the pages go back to equal widths. */
    private fun resetSplitSizes() {
        dividerSizes = null
        setSplitSizes(emptyList())
    }

    /** Keeps the split's sizes (on the phone only) and resizes its pages right away, ahead of the model's update. */
    private fun setSplitSizes(sizes: List<Float>) {
        val split = shown.split ?: return
        components.spaces.update { it.resizeSplit(split.id, sizes) }
        showPanes(shown.copy(split = split.copy(sizes = sizes)))
    }

    /**
     * Whether dropping [tabId] on the page splits them: a single page of the current space shows, and neither
     * tab is an Essential.
     */
    private fun canDropToSplit(tabId: String): Boolean {
        val selected = selectedTabId ?: return false
        if (startScreenVisible || shown.split != null || tabId == selected) return false
        val state = components.spaces.state.value
        val current = state.find(selected)?.place ?: return false
        val dragged = state.find(tabId)?.place ?: return false
        return current !is TabPlace.Essential && dragged !is TabPlace.Essential && current.space?.id == dragged.space?.id
    }

    private val tabDragActions = object : TabDragActions {
        override fun start(tabId: String, finger: Offset) {
            // The hold opened the tab's menu; dragging on puts it away.
            sidebarMenu = null
            tabDrag = TabDrag(tabId, finger)
        }

        override fun move(finger: Offset) {
            tabDrag = tabDrag?.copy(finger = finger)
        }

        override fun drop(finger: Offset) {
            val drag = tabDrag ?: return
            tabDrag = null
            val selected = selectedTabId ?: return
            if (!canDropToSplit(drag.tabId) || !splitDropZone(cardRect, splitGap()).contains(finger)) return
            components.spaces.update { it.splitWith(selected, drag.tabId) }
            root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            // Moving the dragged tab beside the page syncs; the split itself stays on the phone for now.
            components.zenSync.localEdited()
        }

        override fun cancel() {
            tabDrag = null
        }
    }

    /** Opens Mozilla's sign-in page in a Quick Tab; the web channel finishes signing in. */
    private fun signIn() {
        zenSyncSheetOpen = false
        lifecycleScope.launch {
            val url = components.zenSync.beginSignIn() ?: return@launch
            components.tabs.openNewTab(url, quick = true)
            signInTabId = components.store.state.selectedTabId
            startScreenRequested = false
        }
    }

    private val tabSheetActions = object : TabSheetActions {
        override fun select(tabId: String) {
            components.tabs.select(tabId)
            tabSheetOpen = false
            startScreenRequested = false
        }

        override fun close(tabId: String) {
            afterClosing(components.tabs.close(tabId))
            components.zenSync.localEdited()
        }

        override fun unload(tabId: String) = afterClosing(components.tabs.unload(tabId))

        override fun open(input: String) {
            components.tabs.openNewTab(AddressInput.toUrl(input), quick = true)
            tabSheetOpen = false
            startScreenRequested = false
        }

        override fun edit(transform: (SpacesState) -> SpacesState) {
            components.spaces.update(transform)
            components.zenSync.localEdited()
        }

        override fun switchSpace(step: Int) = components.tabs.switchSpace(step)

        override fun addToAnotherSpace(tabId: String) {
            val state = components.spaces.state.value
            val from = state.find(tabId)?.place?.space ?: state.currentSpace
            val others = state.spaces.filter { it.id != from.id }.map { it.toWaveSpace() }
            if (others.isEmpty()) return
            val leftHanded = components.settings.spaceMenuLeftHanded
            val screenHeight = window.decorView.height.toFloat()
            spaceWave.openPicker(tabId, others, from.toWaveSpace().tint, leftHanded, spaceMenuBounds(), screenHeight, Density(this@MainActivity))
            captureWaveBackdrop()
        }

        override fun dismiss() {
            tabSheetOpen = false
        }

        /** When the last tab went, leave the sheet for the start screen. */
        private fun afterClosing(nothingLeft: Boolean) {
            if (nothingLeft) tabSheetOpen = false
        }
    }

    private val sidebarActions = object : SidebarActions {
        override fun setExpanded(expanded: Boolean) = showSidebar(expanded)

        override fun back() = components.sessionUseCases.goBack()

        override fun forward() = components.sessionUseCases.goForward()

        override fun reload() = components.sessionUseCases.reload()

        override fun stop() = components.sessionUseCases.stopLoading()

        override fun setEditing(editing: Boolean) {
            this@MainActivity.editing = editing
        }

        override fun submit(input: String) = this@MainActivity.submit(input)

        override fun openZenSync() {
            zenSyncSheetOpen = true
        }

        override fun setMuted(media: MediaCardState, muted: Boolean) {
            media.controller.muteAudio(muted)
            mutedMedia[media.tabId] = media.controller to muted
        }

        override fun newTab() {
            editing = false
            startScreenRequested = true
            paletteOpen = true
        }

        override fun switchToSpace(spaceId: String) {
            components.tabs.switchTo(spaceId)
            startScreenRequested = false
        }

        override fun switchSpace(step: Int): Boolean {
            if (!components.tabs.switchSpace(step)) return false
            startScreenRequested = false
            return true
        }

        override fun searchFromRail() {
            collapseAfterEdit = true
            showSidebar(true)
            editing = true
        }

        override fun openFolder(folderId: String) {
            components.spaces.update { state ->
                val folder = state.currentSpace.folders.firstOrNull { it.id == folderId }
                if (folder != null && !folder.expanded) state.toggleFolder(folderId) else state
            }
            showSidebar(true)
        }
    }

    /**
     * Desktop sites with the sidebar layout (and the setting on), except in splits unless that's on too; pages
     * switch the next time they load.
     */
    private fun updateDesktopSites() {
        val settings = components.settings
        val mobile = if (settings.desktopSitesInSplit) emptySet() else splitTabIds
        components.desktopSites.setActive(inner && settings.desktopSitesUnfolded, components.store, mobile)
    }

    /** The pages on screen, left to right, and the split they make, if any. */
    private data class Shown(val tabIds: List<String>, val split: Split?)

    /** The selected tab's split while it shows on the inner screen; otherwise the selected tab alone. */
    private fun shownFor(state: SpacesState, selected: String?, inner: Boolean, startScreenRequested: Boolean): Shown {
        selected ?: return Shown(emptyList(), split = null)
        val onScreen = !startScreenRequested && state.isInCurrentSpace(selected, components.settings.essentialsPerContainer)
        val split = state.splitOf(selected)?.takeIf { inner && onScreen }
        return Shown(split?.tabIds ?: listOf(selected), split)
    }

    private fun showPanes(shown: Shown) {
        this.shown = shown
        shown.split?.let { components.tabs.load(it.tabIds) }
        pagePanes.show(shown.tabIds)
        if (inner) layoutInner()
    }

    /** Opens or closes the inner sidebar, which resizes the page card beside it. */
    private fun showSidebar(expanded: Boolean) {
        if (expanded == sidebarExpanded) return
        sidebarExpanded = expanded
        components.settings.innerSidebarExpanded = expanded
        val target = if (expanded) sidebarFullDp else RailWidth.value
        if (inner) animateSidebarTo(target) else sidebarShownDp = target
    }

    /** Slides the sidebar's edge to [target] dp. The page stays under the narrower of the two until it stops. */
    private fun animateSidebarTo(target: Float) {
        sidebarAnimator?.cancel()
        val from = sidebarShownDp
        pinnedPageDp = minOf(from, target)
        sidebarAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = SIDEBAR_ANIMATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                sidebarShownDp = it.animatedValue as Float
                layoutInner()
            }
            doOnEnd {
                if (sidebarAnimator === it) {
                    sidebarAnimator = null
                    pinnedPageDp = null
                    layoutInner()
                }
            }
            start()
        }
    }

    /** Dragging the sidebar's edge: the page goes under the narrowest sidebar until the drag ends. */
    private fun startSidebarResize() {
        pinnedPageDp = SidebarMinWidth.value
        layoutInner()
    }

    private fun resizeSidebar(byDp: Float) {
        sidebarWidthDp = (sidebarFullDp + byDp).coerceIn(SidebarMinWidth.value, maxSidebarDp())
        sidebarFullDp = sidebarWidthDp
        sidebarShownDp = sidebarWidthDp
        layoutInner()
    }

    private fun endSidebarResize() {
        pinnedPageDp = null
        components.settings.innerSidebarWidthDp = sidebarWidthDp
        layoutInner()
    }

    /** The widest the sidebar can be dragged: its maximum, or 60% of the window when that's less. */
    private fun maxSidebarDp(): Float {
        val windowDp = window.decorView.width / resources.displayMetrics.density
        return minOf(SidebarMaxWidth.value, windowDp * 0.6f).coerceAtLeast(SidebarMinWidth.value)
    }

    private val startScreenActions = object : StartScreenActions {
        override fun search(input: String) = openQuickTab(AddressInput.toUrl(input))

        override fun switchTo(tabId: String) {
            components.tabs.select(tabId)
            startScreenRequested = false
        }

        override fun openHistory(url: String) = openQuickTab(url)

        override fun openTabSheet() {
            if (!inner) tabSheetOpen = true
        }

        override suspend fun history(query: String, limit: Int) = withContext(Dispatchers.IO) {
            components.historyStorage.getSuggestions(query, limit).map { HistoryMatch(it.url, it.title.orEmpty()) }
        }

        private fun openQuickTab(url: String) {
            components.tabs.openNewTab(url, quick = true)
            startScreenRequested = false
        }
    }

    /**
     * Whether a hold here opens the space menu: a strip up the wave's edge from the bottom to the top bar (or the
     * top of the screen while the bar is hidden), or the address field while the bar shows (spec: board 3). Not
     * while editing, or over a menu, the palette or the sync sheet.
     */
    private fun spaceMenuOpensAt(x: Float, y: Float): Boolean {
        // Unfolded, the sidebar switches spaces instead (spec: inner-screen gestures).
        if (inner || editing || zenSyncSheetOpen || spaceWave.mode != null || spaceMenuBlocks.count > 0) return false
        if (components.spaces.state.value.spaces.size < 2) return false
        val bar = IntArray(2).also(topBar::getLocationInWindow)
        val barShows = !startScreenVisible && !tabSheetOpen
        val addressField = addressFieldBounds?.takeIf { barShows && !barCollapsed }?.translate(bar[0].toFloat(), bar[1].toFloat())
        val decor = window.decorView
        // Above the gesture handle, so a hold doesn't fight the home gesture.
        val bottom = (decor.height - root.paddingBottom - gestureHandleInset).toFloat()
        val bounds = spaceMenuBounds()
        // The strip stops at the bar's bottom edge, which moves up as the bar slides away.
        val barBottom = if (barShows) (bar[1] + topBar.height).toFloat() else bounds.top
        val stripTop = maxOf(bounds.bottom - bounds.height * components.settings.spaceMenuStripHeight, barBottom)
        val leftHanded = components.settings.spaceMenuLeftHanded
        return SpaceMenuZones.contains(x, y, decor.width.toFloat(), stripTop, bottom, addressField, leftHanded, Density(this))
    }

    private val spaceMenuListener = object : SpaceMenuGesture.Listener {
        override fun onOpen(touch: Offset): Boolean {
            val state = components.spaces.state.value
            val current = state.spaces.indexOfFirst { it.id == state.currentSpaceId }
            if (state.spaces.size < 2 || current < 0) return false
            val spaces = state.spaces.map { it.toWaveSpace() }
            val leftHanded = components.settings.spaceMenuLeftHanded
            val screenHeight = window.decorView.height.toFloat()
            spaceWave.openMenu(spaces, current, touch, leftHanded, spaceMenuBounds(), screenHeight, Density(this@MainActivity))
            captureWaveBackdrop()
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            excludeWaveFromSystemGestures(true)
            return true
        }

        override fun onMove(finger: Offset) {
            if (spaceWave.move(finger)) root.performHapticFeedback(SPACE_TICK)
        }

        override fun onRelease() {
            excludeWaveFromSystemGestures(false)
            spaceWave.release()?.let { spaceId ->
                components.tabs.switchTo(spaceId)
                startScreenRequested = false
            }
        }

        override fun onCancel() {
            excludeWaveFromSystemGestures(false)
            spaceWave.close()
        }
    }

    /**
     * Captures the page for the wave's frosted backdrop; the wave shows frost alone until it arrives. Over the
     * start screen or the tab sheet there's no page on screen to capture.
     */
    private fun captureWaveBackdrop() {
        if (startScreenVisible || tabSheetOpen) return
        val page = engineView.asView()
        val at = IntArray(2).also(page::getLocationInWindow)
        val opening = spaceWave.openings
        engineView.captureThumbnail { bitmap ->
            if (bitmap == null) return@captureThumbnail
            val bounds = Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + bitmap.width).toFloat(), (at[1] + bitmap.height).toFloat())
            spaceWave.showBackdrop(opening, bitmap.asImageBitmap(), bounds)
        }
    }

    /** The screen minus the system bars, in window pixels: where the wave has to fit. */
    private fun spaceMenuBounds(): Rect {
        val decor = window.decorView
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        return Rect(
            left = (bars?.left ?: 0).toFloat(),
            top = (bars?.top ?: 0).toFloat(),
            right = (decor.width - (bars?.right ?: 0)).toFloat(),
            bottom = (decor.height - (bars?.bottom ?: 0)).toFloat(),
        )
    }

    /**
     * While the menu is open, the stretch of the edges beside the wave ignores Android's back gesture (spec:
     * board 3); Android keeps at most 200 dp of it per edge. It only checks this when a touch starts, so it guards
     * the next touch; during the hold itself, the resting finger is what stops the back gesture.
     */
    private fun excludeWaveFromSystemGestures(exclude: Boolean) {
        val rects = if (!exclude || spaceWave.mode == null) {
            emptyList()
        } else {
            val shape = spaceWave.shape(1f)
            val margin = resources.getDimensionPixelSize(R.dimen.top_bar_height)
            val top = minOf(shape.y(0f), spaceWave.finger?.y ?: Float.MAX_VALUE).toInt() - margin
            val bottom = maxOf(shape.y(shape.count - 1f), spaceWave.finger?.y ?: 0f).toInt() + margin
            listOf(android.graphics.Rect(0, top, root.width, bottom))
        }
        ViewCompat.setSystemGestureExclusionRects(root, rects)
    }

    /** "Add to another space": the Quick Tab becomes a normal tab of [spaceId], and Ensō follows it there. */
    private fun addToSpace(tabId: String, spaceId: String) {
        components.spaces.update { it.keepQuickTabIn(tabId, spaceId).copy(currentSpaceId = spaceId).select(tabId) }
        components.tabs.select(tabId)
        tabSheetOpen = false
        startScreenRequested = false
        components.zenSync.localEdited()
    }

    /** The wave's view of a space: tinted with its Zen gradient, or its accent when the theme has no colors. */
    private fun Space.toWaveSpace() =
        WaveSpace(id, name, icon, tint = gradient.map(::ComposeColor).ifEmpty { listOf(ComposeColor(accent)) })

    /** Loads what was typed in the current tab. Over the start screen (the inner sidebar's field), it's a new Quick Tab. */
    private fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        if (startScreenVisible) {
            components.tabs.openNewTab(AddressInput.toUrl(text), quick = true)
            startScreenRequested = false
        } else {
            components.sessionUseCases.loadUrl(AddressInput.toUrl(text))
        }
        editing = false
    }

    private fun observeState() {
        lifecycleScope.launch {
            snapshotFlow { editing }.collect { isEditing ->
                updateBackCallback()
                if (isEditing) {
                    topBar.expand()
                    topBar.disableScrolling()
                } else {
                    topBar.enableScrolling()
                    WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                    if (collapseAfterEdit) {
                        collapseAfterEdit = false
                        showSidebar(false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            // The palette belongs to the start screen; it shouldn't reopen with the next one.
            snapshotFlow { startScreenVisible }.collect { if (!it) paletteOpen = false }
        }
        lifecycleScope.launch {
            components.store.flow(this@MainActivity)
                .map { it.selectedTabId }
                .distinctUntilChanged()
                .collect { selectedTabId = it }
        }
        lifecycleScope.launch {
            // Which pages show: the selected tab, or its whole split on the inner screen.
            combine(
                components.spaces.state,
                components.store.flow(this@MainActivity).map { it.selectedTabId }.distinctUntilChanged(),
                snapshotFlow { inner to startScreenRequested },
            ) { spaces, selected, (isInner, startRequested) -> shownFor(spaces, selected, isInner, startRequested) }
                .distinctUntilChanged()
                .collect(::showPanes)
        }
        lifecycleScope.launch {
            components.spaces.state
                .map { state -> state.spaces.flatMapTo(HashSet()) { space -> space.splits.flatMap { it.tabIds } } }
                .distinctUntilChanged()
                .collect {
                    splitTabIds = it
                    updateDesktopSites()
                }
        }
        lifecycleScope.launch {
            snapshotFlow { spaceWave.mode != null }.collect { spaceWaveView.isVisible = it }
        }
        lifecycleScope.launch {
            snapshotFlow { startScreenVisible to tabSheetOpen }.collect { (startScreen, _) ->
                if (startScreen) editing = false
                updateStatusBar()
            }
        }
        lifecycleScope.launch {
            // Pull from Zen every few minutes while the app is open.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(ZEN_SYNC_INTERVAL_MS)
                    components.zenSync.syncNow()
                }
            }
        }
        lifecycleScope.launch {
            // Close the sign-in page once the account is signed in.
            components.zenSync.status.collect { status ->
                val tab = signInTabId
                if (status is ZenSyncStatus.SignedIn && tab != null) {
                    signInTabId = null
                    components.tabs.close(tab)
                }
            }
        }
        lifecycleScope.launch {
            // While the app is open: keep the Quick Tab on screen fresh, and clear expired ones.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(QUICK_TAB_CHECK_INTERVAL_MS)
                    if (!startScreenVisible) components.tabs.markViewed(shown.tabIds)
                    components.tabs.clearExpiredQuickTabs(components.settings.quickTabLifetimeMs, keep = shown.tabIds)
                }
            }
        }
        lifecycleScope.launch {
            components.store.flow(this@MainActivity)
                .map { it.selectedTab?.content?.canGoBack == true }
                .distinctUntilChanged()
                .collect {
                    canGoBack = it
                    updateBackCallback()
                }
        }
        lifecycleScope.launch {
            // Show the bar again whenever a new page starts loading.
            components.store.flow(this@MainActivity)
                .map { it.selectedTab?.content?.loading == true }
                .distinctUntilChanged()
                .collect { loading -> if (loading) topBar.expand() }
        }
    }

    /** Leave back to the system (closing the app) unless we have an edit to cancel or a page to go back to. */
    private fun updateBackCallback() {
        backCallback.isEnabled = editing || canGoBack
    }

    /**
     * Sizes the bar and (re)installs the behaviors that slide it with the page.
     * Runs again when the insets change, for example when folding or unfolding.
     */
    private fun layoutTopBar(statusBarInset: Int) {
        val height = statusBarInset + resources.getDimensionPixelSize(R.dimen.top_bar_height)
        if (height == barHeight) return
        barHeight = height
        this.statusBarInset = statusBarInset

        topBar.translationY = 0f
        topBar.isVisible = true
        topBar.layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, height).apply {
            gravity = Gravity.TOP
            // Follow the page's real scroll offsets. The touch-gesture variant never sees
            // gestures that start mid-page (GeckoView blocks parent interception), so it
            // can hide the bar but not bring it back.
            behavior = EngineViewScrollingDataBehavior(engineView, topBar, DependencyGravity.Top)
        }
        engineContainer.layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            behavior = EngineViewClippingBehavior(this@MainActivity, null, engineContainer, height, 0)
        }
        topEdge.layoutParams = CoordinatorLayout.LayoutParams(
            MATCH_PARENT,
            maxOf(statusBarInset, resources.getDimensionPixelSize(R.dimen.top_edge_min_height)),
        ).apply { gravity = Gravity.TOP }
        engineView.setDynamicToolbarMaxHeight(height)
        if (!editing) topBar.enableScrolling()
    }

    /**
     * Lays out the inner screen: the page as a card beside the sidebar or rail, inset from the system bars.
     * Runs again when the insets change or the sidebar opens or closes.
     */
    private fun layoutInner() {
        val window = window.decorView
        if (window.width == 0) {
            root.post { if (inner) layoutInner() }
            return
        }
        val density = resources.displayMetrics.density
        // Fit the sidebar to this window (a rotation can leave it too wide) without changing the width the user set.
        sidebarFullDp = minOf(sidebarWidthDp, maxSidebarDp())
        if (sidebarExpanded && pinnedPageDp == null && sidebarAnimator == null) sidebarShownDp = sidebarFullDp
        // The same border on all four sides, between the page and the sidebar too.
        val border = if (components.settings.innerPageBorder) (BORDER_DP * density).toInt() else 0
        val left = barInsets.left + (sidebarShownDp * density).toInt() + border
        val top = barInsets.top + border
        val right = window.width - barInsets.right - border
        val bottom = window.height - barInsets.bottom - border
        cardRect = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        topBar.isVisible = false
        topEdge.isVisible = false
        val pageLeft = pinnedPageDp?.let { barInsets.left + (it * density).toInt() + border } ?: left
        // A split's pages share the card, at the sizes the divider last stopped at.
        val pageArea = Rect(pageLeft.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val rects = paneRects(pageArea, shown.tabIds.size.coerceAtLeast(1), shown.split?.sizes.orEmpty(), splitGap())
        paneViewRects = rects
        pagePanes.panes.drop(1).forEachIndexed { index, pane -> rects.getOrNull(index + 1)?.let { place(pane.container, it) } }
        if (!place(engineContainer, rects.first())) return
        // No bar to slide away on the inner screen, and nothing left over from the cover's one sliding the page.
        engineContainer.translationY = 0f
        engineView.asView().translationY = 0f
        engineView.setDynamicToolbarMaxHeight(0)
        engineView.setVerticalClipping(0)
    }

    /** Puts a page's container at [rect] (window pixels) on the inner screen. False when it was there already. */
    private fun place(container: View, rect: Rect): Boolean {
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        val width = rect.right.roundToInt() - left
        val height = rect.bottom.roundToInt() - top
        val params = container.layoutParams as? CoordinatorLayout.LayoutParams
        val unchanged = params != null && params.behavior == null && params.leftMargin == left && params.topMargin == top &&
            params.width == width && params.height == height
        if (unchanged) return false
        container.layoutParams = CoordinatorLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
        return true
    }

    private fun onTopBarCollapsedChanged(collapsed: Boolean) {
        topEdge.isVisible = collapsed
        updateStatusBar()
    }

    /** The status bar hides with the top bar, but stays up over the start screen and tab sheet. */
    private fun updateStatusBar() {
        val controller = WindowCompat.getInsetsController(window, root)
        if (inner) {
            // Hidden by the same setting, so the page card's thin border runs right to the top.
            if (components.settings.hideStatusBarWithTopBar) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
            return
        }
        if (!components.settings.hideStatusBarWithTopBar) return
        if (barCollapsed && !startScreenVisible && !tabSheetOpen) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * Whether to use the inner screen's layout: a window at least 600 dp wide at its narrowest (so a rotated cover
     * doesn't count), unless it's held in portrait and the setting says portrait uses the cover's layout.
     */
    private fun isInnerScreen(config: Configuration): Boolean {
        if (config.smallestScreenWidthDp < INNER_MIN_WIDTH_DP) return false
        val portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
        return !portrait || components.settings.unfoldedPortraitLayout == UnfoldedPortraitLayout.Desktop
    }

    private companion object {
        const val INNER_MIN_WIDTH_DP = 600

        /** The thin border of space color around the page card, the same on all four sides. */
        const val BORDER_DP = 4

        /** The narrowest a split's page gets when its divider is dragged. */
        const val SPLIT_PAGE_MIN_DP = 240

        /** A navigation bar this short or shorter is gesture navigation's handle, not a bar of buttons. */
        const val GESTURE_NAV_MAX_DP = 32
        const val SIDEBAR_ANIMATION_MS = 240L

        const val QUICK_TAB_CHECK_INTERVAL_MS = 60_000L
        const val ZEN_SYNC_INTERVAL_MS = 5 * 60_000L
        const val ZEN_SYNC_MIN_INTERVAL_MS = 60_000L
        const val EXTRA_RESTORE_ZEN_SNAPSHOT = "enso_restore_zen_snapshot"
        const val EXTRA_DEBUG_OPEN = "enso_debug_open"
        const val EXTRA_DEBUG_DESKTOP_SITES = "enso_debug_desktop_sites"

        /** The tick for each space the space menu's selection moves through. */
        val SPACE_TICK = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }
}
