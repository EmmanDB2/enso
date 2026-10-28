package app.enso.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware

/**
 * Desktop sites while the inner screen shows the sidebar layout (spec: inner screen). GeckoView has no default
 * for this, so each tab's engine session is switched on its own.
 *
 * A tab that gets its engine session while desktop sites apply switches before its first page loads. Folding or
 * unfolding switches the open tabs without reloading them: a page keeps the version it loaded with until it
 * loads again. Tabs in a split can be left out, since each page there is about as narrow as the cover.
 *
 * Some sites always get their mobile version ([alwaysMobile]), decided by the page a tab has when its session
 * starts or the screen changes.
 */
class DesktopSites {
    @Volatile
    private var active = false

    /** Tabs that keep mobile sites even while desktop sites apply. */
    @Volatile
    private var mobileTabs = emptySet<String>()

    private val main = Handler(Looper.getMainLooper())

    /** Runs before the engine middleware links a new session and loads its page. */
    val middleware: Middleware<BrowserState, BrowserAction> = { context, next, action ->
        if (action is EngineAction.LinkEngineSessionAction && active && action.tabId !in mobileTabs &&
            !alwaysMobile(context.state.findTabOrCustomTab(action.tabId)?.content?.url)
        ) {
            val session = action.engineSession
            // Engine sessions are main-thread only; posting from the store's thread still lands before the load,
            // which the engine middleware also posts.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                session.toggleDesktopMode(true, reload = false)
            } else {
                main.post { session.toggleDesktopMode(true, reload = false) }
            }
        }
        next(action)
    }

    /**
     * Switches desktop sites on or off for new tabs and the open ones, except [mobileTabs], without reloading any
     * page. It goes over every tab each time, since a tab can join after its session started (Glance's preview
     * opened as a tab); a session already in the right mode doesn't change.
     */
    fun setActive(desktop: Boolean, store: BrowserStore, mobileTabs: Set<String> = emptySet()) {
        active = desktop
        this.mobileTabs = mobileTabs
        store.state.tabs.forEach { tab ->
            tab.engineState.engineSession?.toggleDesktopMode(desktop && tab.id !in mobileTabs && !alwaysMobile(tab.content.url), reload = false)
        }
    }

    /**
     * Mozilla's add-on site sends a desktop browser to Firefox's desktop add-ons, many of which don't work on
     * Android, so it keeps its mobile version.
     */
    private fun alwaysMobile(url: String?) = url != null && Uri.parse(url).host == ADDONS_HOST

    private companion object {
        const val ADDONS_HOST = "addons.mozilla.org"
    }
}
