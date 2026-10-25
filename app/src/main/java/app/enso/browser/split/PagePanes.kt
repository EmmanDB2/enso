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
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.flow

/**
 * The page views and the tabs they show: one on the cover, or one per page of a split on the inner screen
 * (board 7). The first pane is the cover's page, which the top bar's scrolling behaviors follow.
 *
 * Each view keeps its tab while the focus moves around a split, so pages never jump between views. A GeckoView
 * session can only be in one view at a time, so a view gives its tab up before another one takes it.
 */
class PagePanes(
    private val activity: ComponentActivity,
    private val store: BrowserStore,
    main: EngineView,
    mainContainer: FrameLayout,
    private val createView: () -> EngineView,
    /** Puts a new pane's container in the layout, beside the first one. */
    private val addContainer: (FrameLayout) -> Unit,
    /** A shown tab has no page (an unloaded pinned tab in a split, say); [load] gives it one. */
    private val load: (String) -> Unit,
) {
    inner class Pane(val view: EngineView, val container: FrameLayout) {
        var tabId: String? = null
            private set
        private var job: Job? = null

        fun show(id: String?) {
            if (id == tabId) return
            job?.cancel()
            job = null
            tabId = id
            if (id == null) {
                view.release()
                // The first pane stays: it's the page area behind the start screen too.
                if (this !== panes.first()) container.isVisible = false
                return
            }
            container.isVisible = true
            // As AC's session feature does: render the tab's engine session, asking for one when it has none.
            job = activity.lifecycleScope.launch {
                store.flow(activity)
                    .map { it.findTab(id) }
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
    }

    val panes = mutableListOf(Pane(main, mainContainer))

    /** Shows [tabIds], left to right. Panes past the last one hide. */
    fun show(tabIds: List<String>) {
        while (panes.size < tabIds.size) {
            val view = createView()
            val container = FrameLayout(activity).apply {
                setBackgroundColor(EnsoColors.Page.toArgb())
                addView(view.asView(), MATCH_PARENT, MATCH_PARENT)
                isVisible = false
            }
            addContainer(container)
            panes += Pane(view, container)
        }
        // Free the views whose tab another pane is about to take, before any of them renders.
        panes.forEachIndexed { index, pane ->
            if (pane.tabId != null && pane.tabId != tabIds.getOrNull(index) && pane.tabId in tabIds) pane.show(null)
        }
        panes.forEachIndexed { index, pane -> pane.show(tabIds.getOrNull(index)) }
    }

    fun release() {
        panes.forEach {
            it.show(null)
            it.view.setActivityContext(null)
            it.view.release()
        }
    }
}
