package app.enso.browser

import android.os.Handler
import android.os.Looper
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware

/**
 * Desktop sites while the inner screen shows the sidebar layout (spec: inner screen). GeckoView has no default
 * for this, so each tab's engine session is switched on its own.
 *
 * A tab that gets its engine session while desktop sites apply switches before its first page loads. Folding or
 * unfolding switches the open tabs without reloading them: a page keeps the version it loaded with until it
 * loads again.
 */
class DesktopSites {
    @Volatile
    private var active = false

    private val main = Handler(Looper.getMainLooper())

    /** Runs before the engine middleware links a new session and loads its page. */
    val middleware: Middleware<BrowserState, BrowserAction> = { _, next, action ->
        if (action is EngineAction.LinkEngineSessionAction && active) {
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

    /** Switches desktop sites on or off for new tabs and the open ones, without reloading any page. */
    fun setActive(desktop: Boolean, store: BrowserStore) {
        if (desktop == active) return
        active = desktop
        store.state.tabs.forEach { it.engineState.engineSession?.toggleDesktopMode(desktop, reload = false) }
    }
}
