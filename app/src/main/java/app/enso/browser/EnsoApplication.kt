package app.enso.browser

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.rusthttp.RustHttpConfig

class EnsoApplication : Application() {
    // Lazy so Gecko's own child processes, which also start this
    // Application, never create an engine.
    val components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        // application-services (Places, Mozilla accounts) must be set up before any of it runs,
        // and makes its network requests through the browser's fetch client.
        AppServicesInitializer.init(AppServicesInitializer.Config(crashReporting = null))
        RustHttpConfig.setClient(lazy { components.fetchClient })
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == packageName
    }
}
