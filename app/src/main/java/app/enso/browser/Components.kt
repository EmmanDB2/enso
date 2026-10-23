package app.enso.browser

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import org.mozilla.geckoview.GeckoRuntime

/** App-wide singletons: the engine, the browser state and the actions on it. */
class Components(private val context: Context) {
    // Gecko allows one runtime per process.
    private val runtime by lazy { GeckoRuntime.create(context) }

    val engine by lazy { GeckoEngine(context, runtime = runtime) }
    val store by lazy { BrowserStore(middleware = EngineMiddleware.create(engine)) }
    val sessionUseCases by lazy { SessionUseCases(store) }
    val settings by lazy { Settings(context) }
}
