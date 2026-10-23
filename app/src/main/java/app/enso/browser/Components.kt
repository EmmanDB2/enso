package app.enso.browser

import android.content.Context
import app.enso.browser.spaces.SpacesStore
import app.enso.browser.spaces.TabManager
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime

/** App-wide singletons: the engine, the browser state and the actions on it. */
class Components(private val context: Context) {
    // Gecko allows one runtime per process.
    private val runtime by lazy { GeckoRuntime.create(context) }

    val engine by lazy { GeckoEngine(context, runtime = runtime) }
    val store by lazy { BrowserStore(middleware = EngineMiddleware.create(engine)) }
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val settings by lazy { Settings(context) }
    val spaces by lazy { SpacesStore(context) }
    val tabs by lazy { TabManager(spaces, store, tabsUseCases).also { it.start() } }
}
