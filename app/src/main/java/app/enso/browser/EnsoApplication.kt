package app.enso.browser

import app.enso.browser.permissions.SiteNotifications
import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.rusthttp.RustHttpConfig

class EnsoApplication : Application() {
    // Lazy so Gecko's own child processes, which also start this
    // Application, never create an engine.
    val components by lazy { Components(this) }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        // Debug builds show Android Components' own logs (its icon module, add-on updater and so on) in logcat.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.addSink(AndroidLogSink())
        // application-services (Places, Mozilla accounts) must be set up before any of it runs,
        // and makes its network requests through the browser's fetch client.
        AppServicesInitializer.init(AppServicesInitializer.Config(crashReporting = null))
        RustHttpConfig.setClient(lazy { components.fetchClient })
        // Extensions load with the engine, before any page: uBlock Origin has to be there for the first one.
        components.extensions.start(components.tabs)
        // Pages tell the store their icons through the module's own built-in extension.
        components.icons.install(components.engine, components.store)
        // Sites' notifications (step 11), for the sites the user allowed.
        components.engine.registerWebNotificationDelegate(SiteNotifications(this))
        // The downloads list from earlier runs, for the Downloads page.
        components.downloadsUseCases.restoreDownloads()
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
