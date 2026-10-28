package app.enso.browser.split

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.enso.browser.ui.EnsoColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.flow

/**
 * One page view and the tab it shows, as AC's session feature does it: it renders the tab's engine session, and
 * asks for one when the tab has none. The tab can be a custom tab, as Glance's preview is.
 */
class PageView(
    private val activity: ComponentActivity,
    private val store: BrowserStore,
    val view: EngineView,
    /** Stays visible with no tab, as the page area behind the start screen. */
    private val keepVisible: Boolean,
    /** A shown tab has no page (an unloaded pinned tab in a split, say); [load] gives it one. */
    private val load: (String) -> Unit = {},
) {
    val container = FrameLayout(activity).apply {
        setBackgroundColor(EnsoColors.Page.toArgb())
        addView(view.asView(), MATCH_PARENT, MATCH_PARENT)
        isVisible = keepVisible
    }

    var tabId: String? = null
        private set
    private var job: Job? = null

    /** An engine session shown directly rather than a tab's ([showSession]). */
    private var session: EngineSession? = null

    fun show(id: String?) {
        if (id == tabId && session == null) return
        job?.cancel()
        job = null
        tabId = id
        session = null
        if (id == null) {
            view.release()
            if (!keepVisible) container.isVisible = false
            return
        }
        container.isVisible = true
        job = activity.lifecycleScope.launch {
            store.flow(activity)
                .map { it.findTabOrCustomTab(id) }
                .distinctUntilChangedBy { (it != null) to it?.engineState?.engineSession }
                .collect { tab ->
                    val session = tab?.engineState?.engineSession
                    when {
                        tab == null -> {
                            view.release()
                            load(id)
                        }
                        session == null -> store.dispatch(EngineAction.CreateEngineSessionAction(id))
                        else -> view.render(session)
                    }
                }
        }
    }

    /** Shows an engine session that isn't a tab's: an extension's popup. */
    fun showSession(session: EngineSession) {
        job?.cancel()
        job = null
        tabId = null
        this.session = session
        container.isVisible = true
        view.render(session)
    }

    fun release() {
        show(null)
        view.setActivityContext(null)
        view.release()
    }

    /** Its blank page's color again, after Ensō switches between light and dark. */
    fun recolor() = container.setBackgroundColor(EnsoColors.Page.toArgb())
}

/**
 * The page views and the tabs they show: one on the cover, or one per page of a split on the inner screen
 * (board 7). The first pane is the cover's page, which the bars' scrolling behaviors follow.
 *
 * Each view keeps its tab while the focus moves around a split, so pages never jump between views. A GeckoView
 * session can only be in one view at a time, so a view gives its tab up before another one takes it.
 */
class PagePanes(
    private val activity: ComponentActivity,
    private val store: BrowserStore,
    main: EngineView,
    private val createView: () -> EngineView,
    /** Puts a new pane's container in the layout, beside the first one. */
    private val addContainer: (FrameLayout) -> Unit,
    private val load: (String) -> Unit,
) {
    val panes = mutableListOf(PageView(activity, store, main, keepVisible = true, load))

    /** Shows [tabIds], left to right. Panes past the last one hide. */
    fun show(tabIds: List<String>) {
        while (panes.size < tabIds.size) {
            panes += PageView(activity, store, createView(), keepVisible = false, load).also { addContainer(it.container) }
        }
        // Free the views whose tab another pane is about to take, before any of them renders.
        panes.forEachIndexed { index, pane ->
            if (pane.tabId != null && pane.tabId != tabIds.getOrNull(index) && pane.tabId in tabIds) pane.show(null)
        }
        panes.forEachIndexed { index, pane -> pane.show(tabIds.getOrNull(index)) }
    }

    fun release() = panes.forEach { it.release() }

    fun recolor() = panes.forEach { it.recolor() }
}
