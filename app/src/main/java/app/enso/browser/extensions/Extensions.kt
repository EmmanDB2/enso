package app.enso.browser.extensions

import android.content.Context
import android.util.Log
import app.enso.browser.Settings
import app.enso.browser.spaces.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.webextensions.WebExtensionSupport
import java.io.File

/**
 * WebExtensions (step 9). AC's extension support keeps the installed extensions in the store, with their toolbar
 * actions, popups and options pages. This installs the bundled uBlock Origin once and answers its install prompt,
 * and sends the tabs extensions open, close or select through [TabManager].
 */
class Extensions(
    private val context: Context,
    private val engine: GeckoEngine,
    private val store: BrowserStore,
    private val settings: Settings,
) {
    private val scope = MainScope()

    /** Extensions Ensō is installing itself, whose permission prompt it answers: the user didn't ask for them. */
    private val installingBundled = mutableSetOf<String>()

    fun start(tabs: TabManager) {
        WebExtensionSupport.initialize(
            runtime = engine,
            store = store,
            onNewTabOverride = { _, session, url, selected, _ -> tabs.openExtensionTab(url, session, selected) },
            onCloseTabOverride = { _, tabId -> tabs.close(tabId) },
            onSelectTabOverride = { _, tabId -> tabs.select(tabId) },
        )
        scope.launch {
            store.flow().map { it.webExtensionPromptRequest }.distinctUntilChanged().collect { request ->
                if (request is WebExtensionPromptRequest.AfterInstallation.Permissions.Required &&
                    request.extension.id in installingBundled
                ) {
                    request.onConfirm(PermissionPromptResponse(true, false, false))
                    store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                }
            }
        }
        installBundled()
    }

    /** Installed extensions as the engine knows them, by id: what enabling, disabling and removing act on. */
    fun installed(id: String): WebExtension? = WebExtensionSupport.installedExtensions[id]

    fun setEnabled(id: String, enabled: Boolean) {
        val extension = installed(id) ?: return
        val failed: (Throwable) -> Unit = { error -> Log.w(TAG, "Couldn't ${if (enabled) "enable" else "disable"} $id", error) }
        if (enabled) {
            engine.enableWebExtension(extension, EnableSource.USER, onSuccess = {}, onError = failed)
        } else {
            engine.disableWebExtension(extension, EnableSource.USER, onSuccess = {}, onError = failed)
        }
    }

    fun uninstall(id: String) {
        val extension = installed(id) ?: return
        engine.uninstallWebExtension(extension, onSuccess = {}, onError = { _, error -> Log.w(TAG, "Couldn't remove $id", error) })
    }

    /**
     * uBlock Origin ships in the app as Mozilla's signed package and installs on first launch, so it works offline.
     * After that it's an ordinary extension, which the user can turn off or remove: it isn't installed again.
     *
     * AC won't install from a `resource:` URL (GeckoView would), so the package is copied out of the APK's assets
     * into the app's files first and installed from there.
     */
    private fun installBundled() {
        if (settings.bundledExtensionsInstalled) return
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, UBLOCK_ORIGIN_ASSET.substringAfterLast('/')).also { file ->
                    context.assets.open(UBLOCK_ORIGIN_ASSET).use { input -> file.outputStream().use(input::copyTo) }
                }
            }
            installingBundled += UBLOCK_ORIGIN_ID
            engine.installWebExtension(
                file.toURI().toString(),
                InstallationMethod.FROM_FILE,
                onSuccess = {
                    Log.i(TAG, "Installed ${it.id}")
                    installingBundled -= it.id
                    settings.bundledExtensionsInstalled = true
                    file.delete()
                },
                onError = { error ->
                    installingBundled -= UBLOCK_ORIGIN_ID
                    Log.w(TAG, "Couldn't install the bundled uBlock Origin", error)
                    file.delete()
                },
            )
        }
    }

    companion object {
        private const val TAG = "Extensions"
        const val UBLOCK_ORIGIN_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_ORIGIN_ASSET = "extensions/ublock_origin.xpi"
    }
}
