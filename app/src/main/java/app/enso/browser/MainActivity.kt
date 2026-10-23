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
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.TopBar
import app.enso.browser.toolbar.TopBarState
import app.enso.browser.toolbar.TopBarView
import app.enso.browser.toolbar.toTopBarState
import app.enso.browser.ui.EnsoColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.session.SessionFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.widgets.behavior.DependencyGravity
import mozilla.components.ui.widgets.behavior.EngineViewClippingBehavior
import mozilla.components.ui.widgets.behavior.EngineViewScrollingDataBehavior

/** Cover-screen browser (build step 2): one tab, the tinted top bar and the page. */
class MainActivity : ComponentActivity() {
    private val components get() = (application as EnsoApplication).components

    private lateinit var root: CoordinatorLayout
    private lateinit var engineView: EngineView
    private lateinit var engineContainer: FrameLayout
    private lateinit var topBar: TopBarView
    private lateinit var topEdge: View
    private lateinit var sessionFeature: SessionFeature

    private var editing by mutableStateOf(false)
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

        if (components.store.state.selectedTab == null) {
            val lastUrl = components.settings.lastUrl
            val tab = createTab(lastUrl ?: AddressInput.BLANK_PAGE)
            components.store.dispatch(TabListAction.AddTabAction(tab, select = true))
            editing = lastUrl == null
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
        val immersive = components.settings.hideStatusBarWithTopBar
        root = CoordinatorLayout(this).apply {
            val chrome = if (immersive) EnsoColors.Page else EnsoColors.spaceTint(EnsoColors.DefaultSpaceAccent)
            setBackgroundColor(chrome.toArgb())
            addView(engineContainer)
            addView(topBar)
            addView(topEdge)
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
        onBackPressedDispatcher.addCallback(this, backCallback)
        observeState()
    }

    override fun onStart() {
        super.onStart()
        sessionFeature.start()
    }

    override fun onStop() {
        super.onStop()
        sessionFeature.stop()
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
        val useCases = components.sessionUseCases
        TopBar(
            state = state,
            editing = editing,
            statusBarInset = with(LocalDensity.current) { statusBarInset.toDp() },
            onEditingChange = { editing = it },
            onSubmit = ::submit,
            onReload = { useCases.reload() },
            onStop = { useCases.stopLoading() },
            onBack = { useCases.goBack() },
            onForward = { useCases.goForward() },
        )
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
            // Saved on every change so the page survives the process being killed in any state.
            components.store.flow(this@MainActivity)
                .map { it.selectedTab?.content?.url }
                .distinctUntilChanged()
                .collect { url ->
                    if (url != null && url != AddressInput.BLANK_PAGE) components.settings.lastUrl = url
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
