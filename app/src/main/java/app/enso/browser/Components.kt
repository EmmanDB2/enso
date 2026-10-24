package app.enso.browser

import android.content.Context
import app.enso.browser.spaces.SpacesStore
import app.enso.browser.spaces.TabManager
import app.enso.browser.sync.FetchSyncHttp
import app.enso.browser.sync.MozillaAccount
import app.enso.browser.sync.ZenSync
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime

/** App-wide singletons: the engine, the browser state and the actions on it. */
class Components(private val context: Context) {
    // Gecko allows one runtime per process.
    private val runtime by lazy { GeckoRuntime.create(context) }

    /** HTTP through Gecko, for application-services and Zen sync. */
    val fetchClient by lazy { GeckoViewFetchClient(context, runtime) }

    /** Firefox's Places database; the search palette reads it and step 5 syncs it. */
    val historyStorage by lazy { PlacesHistoryStorage(context) }

    val engine by lazy {
        val settings = DefaultSettings(historyTrackingDelegate = HistoryDelegate(lazy { historyStorage }))
        GeckoEngine(context, settings, runtime)
    }
    val store by lazy { BrowserStore(middleware = EngineMiddleware.create(engine)) }
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val settings by lazy { Settings(context) }
    val spaces by lazy { SpacesStore(context) }
    val tabs by lazy { TabManager(spaces, store, tabsUseCases).also { it.start() } }

    val accountManager by lazy { MozillaAccount.accountManager(context) }
    val zenSync by lazy { ZenSync(context, accountManager, FetchSyncHttp(fetchClient), tabs).also { it.start() } }
}
