package app.enso.browser

import android.os.Handler
import android.os.Looper
import app.enso.browser.settings.SiteChoices
import app.enso.browser.settings.SiteChoices.Companion.hostOf
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
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
 * starts or the screen changes. Above all of it, the user can pick desktop or mobile for a site in its site settings
 * ([choices]), which holds on either screen.
 */
class DesktopSites(private val choices: SiteChoices) {
    @Volatile
    private var active = false

    /** Tabs that keep mobile sites even while desktop sites apply. */
    @Volatile
    private var mobileTabs = emptySet<String>()

    private val main = Handler(Looper.getMainLooper())

    /** Runs before the engine middleware links a new session and loads its page. */
    val middleware: Middleware<BrowserState, BrowserAction> = { context, next, action ->
        if (action is EngineAction.LinkEngineSessionAction &&
            wantsDesktop(action.tabId, context.state.findTabOrCustomTab(action.tabId)?.content?.url)
        ) {
            val session = action.engineSession
            // Engine sessions are main-thread only; posting from the store's thread still lands before the load,
            // which the engine middleware also posts.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                session.toggleDesktopMode(true, reload = false)
            } else {
                main.post { session.toggleDesktopMode(true, reload = false) }
            }
            // AC only reports a switch to the store through the observer it registers while linking, after this,
            // so the tab's desktopMode (which site settings show) would stay false.
            context.dispatch(ContentAction.UpdateTabDesktopMode(action.tabId, true))
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
            tab.engineState.engineSession?.toggleDesktopMode(wantsDesktop(tab.id, tab.content.url), reload = false)
        }
    }

    /** Whether a tab showing [url] gets the desktop site: the user's choice for the site, else what the screen gives. */
    fun wantsDesktop(tabId: String, url: String?): Boolean = choices.desktop(hostOf(url)) ?: screenGivesDesktop(tabId, url)

    /** Whether the user picked desktop or mobile for [url]'s site, rather than letting it follow the screen. */
    fun chosen(url: String?): Boolean = choices.desktop(hostOf(url)) != null

    /**
     * The user picked [desktop] for the site of the tab on screen. It's remembered for the site, unless it's what
     * the screen gives anyway, which forgets the choice. The site's open tabs switch, and the one on screen reloads.
     */
    fun choose(store: BrowserStore, tabId: String, url: String, desktop: Boolean) {
        val host = hostOf(url) ?: return
        choices.setDesktop(host, desktop.takeIf { it != screenGivesDesktop(tabId, url) })
        store.state.tabs.filter { hostOf(it.content.url) == host }.forEach { tab ->
            tab.engineState.engineSession?.toggleDesktopMode(wantsDesktop(tab.id, tab.content.url), reload = tab.id == tabId)
        }
    }

    private fun screenGivesDesktop(tabId: String, url: String?) = active && tabId !in mobileTabs && !alwaysMobile(url)

    /**
     * Mozilla's add-on site sends a desktop browser to Firefox's desktop add-ons, many of which don't work on
     * Android, so it keeps its mobile version.
     */
    private fun alwaysMobile(url: String?) = hostOf(url) == ADDONS_HOST

    private companion object {
        const val ADDONS_HOST = "addons.mozilla.org"
    }
}
