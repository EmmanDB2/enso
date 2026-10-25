package app.enso.browser.toolbar

import android.content.Context
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.ui.widgets.behavior.EngineViewScrollingBehavior

/**
 * A view that Android Components' scrolling and clipping behaviors slide with the page: the bottom bar's anchor,
 * whose movement the bar drawn over the page follows, and the band under the status bar.
 */
class ScrollingBarView(context: Context) : FrameLayout(context), ScrollableToolbar {
    private val behavior: EngineViewScrollingBehavior?
        get() = (layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? EngineViewScrollingBehavior

    override fun enableScrolling() {
        behavior?.enableScrolling()
    }

    override fun disableScrolling() {
        behavior?.disableScrolling()
    }

    override fun expand() {
        behavior?.forceExpand()
    }

    override fun collapse() {
        behavior?.forceCollapse()
    }
}
