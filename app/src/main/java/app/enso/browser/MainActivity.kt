package app.enso.browser

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.enso.browser.spaces.SpacesState
import app.enso.browser.tabsheet.TabSheet
import app.enso.browser.tabsheet.TabSheetActions
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.TopBar
import app.enso.browser.toolbar.TopBarState
import app.enso.browser.toolbar.TopBarView
import app.enso.browser.toolbar.toTopBarState
import app.enso.browser.ui.EnsoColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.widgets.behavior.DependencyGravity
import mozilla.components.ui.widgets.behavior.EngineViewClippingBehavior
import mozilla.components.ui.widgets.behavior.EngineViewScrollingDataBehavior
import androidx.compose.ui.graphics.Color as ComposeColor

/** Cover-screen browser: the tinted top bar, the page, and the tab sheet over them. */
class MainActivity : ComponentActivity() {
    private val components get() = (application as EnsoApplication).components

    private lateinit var root: CoordinatorLayout
    private lateinit var engineView: EngineView
    private lateinit var engineContainer: FrameLayout
    private lateinit var topBar: TopBarView
    private lateinit var topEdge: View
    private lateinit var sessionFeature: SessionFeature
    private lateinit var windowFeature: WindowFeature

    private var editing by mutableStateOf(false)
    private var tabSheetOpen by mutableStateOf(false)
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

        // Until the start screen (step 4), an empty space opens a blank tab ready for typing.
        if (components.store.state.selectedTab == null && !components.tabs.restore()) {
            components.tabs.openNewTab(AddressInput.BLANK_PAGE)
            editing = true
        }

        engineView = components.engine.createView(this)
        engineView.setActivityContext(this)
        engineContainer = FrameLayout(this).apply {
            setBackgroundColor(EnsoColors.Page.toArgb())
            addView(engineView.asView(), MATCH_PARENT, MATCH_PARENT)
        }
        topBar = TopBarView(this).apply { setContent { TopBarContent() } }
        topEdge = View(this).apply {
            isVisible = false
            contentDescription = getString(R.string.action_show_top_bar)
            setOnClickListener { topBar.expand() }
        }
        val tabSheet = ComposeView(this).apply { setContent { TabSheetContent() } }
        val immersive = components.settings.hideStatusBarWithTopBar
        root = CoordinatorLayout(this).apply {
            val accent = ComposeColor(components.spaces.state.value.currentSpace.accent)
            val chrome = if (immersive) EnsoColors.Page else EnsoColors.spaceTint(accent)
            setBackgroundColor(chrome.toArgb())
            addView(engineContainer)
            addView(topBar)
            addView(topEdge)
            addView(tabSheet, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            val edges = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = edges.left,
                top = if (immersive) 0 else statusTop,
                right = edges.right,
                bottom = edges.bottom,
            )
            layoutTopBar(statusBarInset = if (immersive) statusTop else 0)
            // Back with the keyboard up only closes the keyboard; treat that as cancelling the edit too.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) editing = false
            imeWasVisible = imeVisible
            WindowInsetsCompat.CONSUMED
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
        onBackPressedDispatcher.addCallback(this, backCallback)
        observeState()
    }

    override fun onStart() {
        super.onStart()
        sessionFeature.start()
        windowFeature.start()
    }

    override fun onStop() {
        super.onStop()
        sessionFeature.stop()
        windowFeature.stop()
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
            accent = ComposeColor(spaces.currentSpace.accent),
            editing = editing,
            statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() },
            onEditingChange = { editing = it },
            onSubmit = ::submit,
            onReload = { useCases.reload() },
            onStop = { useCases.stopLoading() },
            onBack = { useCases.goBack() },
            onForward = { useCases.goForward() },
            onOpenTabSheet = { tabSheetOpen = true },
            onSwitchTab = { step -> components.tabs.switchBy(step, components.settings.tabSwipeScope) },
        )
    }

    @Composable
    private fun TabSheetContent() {
        val spaces by components.spaces.state.collectAsState()
        val selectedTabId by components.store.observeAsComposableState { it.selectedTabId }
        AnimatedVisibility(
            visible = tabSheetOpen,
            enter = fadeIn() + slideInVertically { -it / 12 },
            exit = fadeOut() + slideOutVertically { -it / 12 },
        ) {
            BackHandler { tabSheetOpen = false }
            TabSheet(
                spaces = spaces,
                selectedTabId = selectedTabId,
                statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() },
                actions = tabSheetActions,
            )
        }
    }

    private val tabSheetActions = object : TabSheetActions {
        override fun select(tabId: String) {
            components.tabs.select(tabId)
            tabSheetOpen = false
        }

        override fun close(tabId: String) = afterClosing(components.tabs.close(tabId))

        override fun unload(tabId: String) = afterClosing(components.tabs.unload(tabId))

        override fun open(input: String) {
            components.tabs.openNewTab(AddressInput.toUrl(input))
            tabSheetOpen = false
        }

        override fun edit(transform: (SpacesState) -> SpacesState) = components.spaces.update(transform)

        override fun dismiss() {
            tabSheetOpen = false
        }

        /** When the last tab went, a blank one replaced it: leave the sheet and start typing. */
        private fun afterClosing(openedBlank: Boolean) {
            if (openedBlank) {
                tabSheetOpen = false
                editing = true
            }
        }
    }

    private fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        components.sessionUseCases.loadUrl(AddressInput.toUrl(text))
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

    private fun onTopBarCollapsedChanged(collapsed: Boolean) {
        topEdge.isVisible = collapsed
        if (components.settings.hideStatusBarWithTopBar) {
            val controller = WindowCompat.getInsetsController(window, root)
            if (collapsed) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
