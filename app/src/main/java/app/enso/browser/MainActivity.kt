package app.enso.browser

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.enso.browser.sidebar.Rail
import app.enso.browser.sidebar.RailWidth
import app.enso.browser.sidebar.Sidebar
import app.enso.browser.sidebar.SidebarActions
import app.enso.browser.sidebar.SidebarWidth
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
import app.enso.browser.spaces.essentialsFor
import app.enso.browser.spaces.find
import app.enso.browser.spaces.isInCurrentSpace
import app.enso.browser.spaces.keepQuickTabIn
import app.enso.browser.spaces.select
import app.enso.browser.spaces.space
import app.enso.browser.spaces.toggleFolder
import app.enso.browser.start.HistoryMatch
import app.enso.browser.start.StartScreen
import app.enso.browser.start.StartScreenActions
import app.enso.browser.sync.MozillaAccount
import app.enso.browser.sync.ZenSyncSheet
import app.enso.browser.sync.ZenSyncStatus
import app.enso.browser.tabsheet.MenuTarget
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.accounts.FxaWebChannelFeature
import mozilla.components.feature.session.SessionFeature
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
    private lateinit var engineView: EngineView
    private lateinit var engineContainer: FrameLayout
    private lateinit var topBar: TopBarView
    private lateinit var topEdge: View
    private lateinit var sessionFeature: SessionFeature
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

    /** The rail's search button opened the sidebar, so it closes again when the edit ends. */
    private var collapseAfterEdit = false

    /** The page card on the inner screen, in window pixels. */
    private var cardRect by mutableStateOf(Rect.Zero)

    /** The system bars and cutout around the window: the status bar on top, the navigation bar below. */
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
    private var barHeight = 0
    private var barCollapsed = false
    private var canGoBack = false
    private var imeWasVisible = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (editing) editing = false else sessionFeature.onBackPressed()
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

        // With nothing to restore, the start screen shows.
        if (components.store.state.selectedTab == null) components.tabs.restore()
        selectedTabId = components.store.state.selectedTabId
        inner = isInnerScreen(resources.configuration)
        sidebarExpanded = components.settings.innerSidebarExpanded

        engineView = components.engine.createView(this)
        engineView.setActivityContext(this)
        engineContainer = FrameLayout(this).apply {
            setBackgroundColor(EnsoColors.Page.toArgb())
            addView(engineView.asView(), MATCH_PARENT, MATCH_PARENT)
        }
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
            barInsets = Insets.of(bars.left, statusTop, bars.right, bars.bottom)
            if (inner) {
                // The frame and sidebar draw under the system bars themselves; only the keyboard moves things up.
                view.updatePadding(left = 0, top = 0, right = 0, bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                layoutInner()
            } else {
                view.updatePadding(
                    left = edges.left,
                    top = if (immersive) 0 else statusTop,
                    right = edges.right,
                    bottom = edges.bottom,
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

        sessionFeature = SessionFeature(
            components.store,
            components.sessionUseCases.goBack,
            components.sessionUseCases.goForward,
            engineView,
        )
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        spaceMenuGesture.onTouchEvent(event) || super.dispatchTouchEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n app.enso.browser/.MainActivity --ez enso_restore_zen_snapshot true`
     * puts Zen's spaces collection back as it was before the phone's first write.
     */
    private fun handleDebugIntent(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra(EXTRA_RESTORE_ZEN_SNAPSHOT, false) == true) {
            intent.removeExtra(EXTRA_RESTORE_ZEN_SNAPSHOT)
            components.zenSync.restoreSnapshot()
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = components.settings
        // Anything that expired while we were away goes, even the tab that was on screen.
        components.tabs.clearExpiredQuickTabs(settings.quickTabLifetimeMs, keep = null)
        val away = settings.lastStoppedAt?.let { System.currentTimeMillis() - it }
        if (away != null && away >= settings.startScreenAfterMs) startScreenRequested = true
        sessionFeature.start()
        windowFeature.start()
        webChannelFeature.start()
        components.zenSync.syncNow(minIntervalMs = ZEN_SYNC_MIN_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        sessionFeature.stop()
        windowFeature.stop()
        webChannelFeature.stop()
        spaceMenuGesture.reset()
        spaceWave.close()
        if (!startScreenVisible) components.tabs.markSelectedViewed()
        components.settings.lastStoppedAt = System.currentTimeMillis()
        components.spaces.flush()
    }

    override fun onDestroy() {
        sessionFeature.release()
        engineView.setActivityContext(null)
        engineView.release()
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
        InnerFrame(spaces.currentSpace.colors(), cardRect, page)
        val edges = with(density) { Modifier.padding(top = barInsets.top.toDp(), bottom = barInsets.bottom.toDp()).padding(start = barInsets.left.toDp()) }
        if (sidebarExpanded) {
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
                modifier = edges,
            )
        } else {
            Rail(
                spaces = spaces,
                essentials = spaces.essentialsFor(perContainer = perContainer),
                selectedTabId = selectedTabId,
                newTabShowing = startScreenVisible,
                tabActions = tabSheetActions,
                actions = sidebarActions,
                onMenu = { sidebarMenu = it },
                modifier = edges,
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

    /** Opens or closes the inner sidebar, which resizes the page card beside it. */
    private fun showSidebar(expanded: Boolean) {
        if (expanded == sidebarExpanded) return
        sidebarExpanded = expanded
        components.settings.innerSidebarExpanded = expanded
        if (inner) layoutInner()
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
        val bottom = (decor.height - root.paddingBottom).toFloat()
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
                    if (!startScreenVisible) components.tabs.markSelectedViewed()
                    components.tabs.clearExpiredQuickTabs(components.settings.quickTabLifetimeMs, keep = selectedTabId)
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
        val sidebar = ((if (sidebarExpanded) SidebarWidth else RailWidth).value * density).toInt()
        val margin = (CARD_MARGIN_DP * density).toInt()
        val left = barInsets.left + sidebar + (CARD_GAP_DP * density).toInt()
        val top = barInsets.top + margin
        val right = window.width - barInsets.right - margin
        val bottom = window.height - barInsets.bottom - margin
        cardRect = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        topBar.isVisible = false
        topEdge.isVisible = false
        engineContainer.layoutParams = CoordinatorLayout.LayoutParams(right - left, bottom - top).apply {
            leftMargin = left
            topMargin = top
        }
        // No bar to slide away on the inner screen, and nothing left over from the cover's one sliding the page.
        engineContainer.translationY = 0f
        engineView.asView().translationY = 0f
        engineView.setDynamicToolbarMaxHeight(0)
        engineView.setVerticalClipping(0)
    }

    private fun onTopBarCollapsedChanged(collapsed: Boolean) {
        topEdge.isVisible = collapsed
        updateStatusBar()
    }

    /** The status bar hides with the top bar, but stays up over the start screen and tab sheet. */
    private fun updateStatusBar() {
        val controller = WindowCompat.getInsetsController(window, root)
        if (inner) {
            controller.show(WindowInsetsCompat.Type.statusBars())
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

        /** The page card's margin from the screen's edges, and its gap from the sidebar (boards 5 and 6). */
        const val CARD_MARGIN_DP = 10
        const val CARD_GAP_DP = 4

        const val QUICK_TAB_CHECK_INTERVAL_MS = 60_000L
        const val ZEN_SYNC_INTERVAL_MS = 5 * 60_000L
        const val ZEN_SYNC_MIN_INTERVAL_MS = 60_000L
        const val EXTRA_RESTORE_ZEN_SNAPSHOT = "enso_restore_zen_snapshot"

        /** The tick for each space the space menu's selection moves through. */
        val SPACE_TICK = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }
}
