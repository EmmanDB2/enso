package app.enso.browser

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime

class EnsoApplication : Application() {
    // Gecko allows one runtime per process. Lazy so Gecko's own child
    // processes, which also start this Application, never create one.
    val geckoRuntime: GeckoRuntime by lazy { GeckoRuntime.create(this) }
}
