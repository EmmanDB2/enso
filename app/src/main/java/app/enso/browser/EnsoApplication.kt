package app.enso.browser

import android.app.Application

class EnsoApplication : Application() {
    // Lazy so Gecko's own child processes, which also start this
    // Application, never create an engine.
    val components by lazy { Components(this) }
}
