package app.enso.browser.toolbar

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.ui.widgets.behavior.EngineViewScrollingBehavior

/**
 * Hosts the Compose top bar. Being a [ScrollableToolbar] lets Android Components'
 * scrolling and clipping behaviors slide it with the page.
 */
class TopBarView(context: Context) : FrameLayout(context), ScrollableToolbar {
    private val composeView = ComposeView(context).also { addView(it) }

    private val behavior: EngineViewScrollingBehavior?
        get() = (layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? EngineViewScrollingBehavior

    fun setContent(content: @Composable () -> Unit) = composeView.setContent(content)

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
