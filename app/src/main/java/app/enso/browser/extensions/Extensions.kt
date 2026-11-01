package app.enso.browser.extensions

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.enso.browser.R
import app.enso.browser.Settings
import app.enso.browser.spaces.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import mozilla.components.concept.engine.webextension.WebExtensionInstallException
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.AddonsProvider
import mozilla.components.feature.addons.update.DefaultAddonUpdater
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.worker.Frequency
import mozilla.components.support.webextensions.WebExtensionSupport
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WebExtensions (step 9). AC's extension support keeps the installed extensions in the store, with their toolbar
 * actions, popups and options pages. This installs the bundled uBlock Origin once and answers its install prompt,
 * sends the tabs extensions open, close or select through [TabManager], puts Gecko's other questions to the user
 * ([prompts]), and checks for updates daily.
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

    /** Extensions the user agreed to install, which get an "added" notice once they're in. */
    private val addedByUser = mutableSetOf<String>()

    private val queue = MutableStateFlow<List<ExtensionPrompt>>(emptyList())

    /** Questions and notices for the user, oldest first. The app shows the first and [answer]s it. */
    val prompts: StateFlow<List<ExtensionPrompt>> = queue.asStateFlow()

    /** AC's updater: a WorkManager job per extension, once a day. */
    private val updater = DefaultAddonUpdater(
        context,
        Frequency(1, TimeUnit.DAYS),
        // It only posts a notification for an update that wants more permissions, which Ensō asks in the app instead.
        NotificationsDelegate(NotificationManagerCompat.from(context)),
    )

    fun start(tabs: TabManager) {
        // The updater's jobs update through the add-on manager they find here.
        GlobalAddonDependencyProvider.initialize(AddonManager(store, engine, NoFeaturedAddons, updater), updater) { error ->
            Log.w(TAG, "An extension update failed", error)
        }
        WebExtensionSupport.initialize(
            runtime = engine,
            store = store,
            onNewTabOverride = { _, session, url, selected, _ -> tabs.openExtensionTab(url, session, selected) },
            onCloseTabOverride = { _, tabId -> tabs.close(tabId) },
            onSelectTabOverride = { _, tabId -> tabs.select(tabId) },
            onUpdatePermissionRequest = ::askToUpdate,
        )
        scope.launch {
            store.flow().map { it.webExtensionPromptRequest }.distinctUntilChanged().collect { request ->
                if (request != null && handle(request)) store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
            }
        }
        scope.launch { scheduleUpdates() }
        installBundled()
    }

    /** Passes the user's answer on, once: a tap on the card while it fades out answers nothing. */
    fun answer(prompt: ExtensionPrompt, yes: Boolean) {
        var waiting = false
        queue.update { prompts ->
            waiting = prompt in prompts
            prompts - prompt
        }
        if (waiting) prompt.answer(yes)
    }

    /** Turns one of Gecko's install requests into a prompt, or answers it quietly. False if it isn't one Ensō handles. */
    private fun handle(request: WebExtensionPromptRequest): Boolean {
        when (request) {
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                val extension = request.extension
                if (extension.id in installingBundled) {
                    request.onConfirm(PermissionPromptResponse(true, false, false))
                } else {
                    ask(
                        ExtensionPrompt(
                            ExtensionPrompt.Kind.Install,
                            extension.id,
                            nameOf(extension),
                            describe(request.permissions, request.origins, forUpdate = false),
                            describeDataCollection(request.dataCollectionPermissions),
                            loadIcon = extension::loadIcon,
                        ) { yes ->
                            if (yes) addedByUser += extension.id
                            // No private browsing to allow it in, and the optional technical data stays unshared.
                            request.onConfirm(PermissionPromptResponse(yes, false, false))
                        },
                    )
                }
            }
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> ask(
                ExtensionPrompt(
                    ExtensionPrompt.Kind.MorePermissions,
                    request.extension.id,
                    nameOf(request.extension),
                    describe(request.permissions, request.origins, forUpdate = false),
                    describeDataCollection(request.dataCollectionPermissions),
                    loadIcon = request.extension::loadIcon,
                    answer = request.onConfirm,
                ),
            )
            // Updates install too; only an install the user just agreed to gets the notice.
            is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> if (addedByUser.remove(request.extension.id)) {
                ask(ExtensionPrompt(ExtensionPrompt.Kind.Added, request.extension.id, nameOf(request.extension), loadIcon = request.extension::loadIcon))
            }
            is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed -> {
                val error = request.exception
                val id = request.extension?.id ?: error.extensionId
                addedByUser -= id.orEmpty()
                // The user saw no prompt for a bundled install, and cancelling needs no notice.
                if (error !is WebExtensionInstallException.UserCancelled && (id == null || id !in installingBundled)) {
                    val name = request.extension?.let(::nameOf) ?: error.extensionName ?: context.getString(R.string.extension_unnamed)
                    ask(ExtensionPrompt(ExtensionPrompt.Kind.Failed, id, name, failure = failureReason(error)))
                }
            }
            else -> return false
        }
        return true
    }

    /**
     * An update that wants more permissions waits for the user's OK, and the current version stays until then. A
     * newer check replaces an unanswered one.
     */
    private fun askToUpdate(
        extension: WebExtension,
        permissions: List<String>,
        origins: List<String>,
        dataCollection: List<String>,
        onPermissionsGranted: (Boolean) -> Unit,
    ) {
        queue.value.filter { it.kind == ExtensionPrompt.Kind.Update && it.extensionId == extension.id }.forEach { answer(it, false) }
        ask(
            ExtensionPrompt(
                ExtensionPrompt.Kind.Update,
                extension.id,
                nameOf(extension),
                describe(permissions, origins, forUpdate = true),
                describeDataCollection(dataCollection),
                loadIcon = extension::loadIcon,
                answer = onPermissionsGranted,
            ),
        )
    }

    private fun ask(prompt: ExtensionPrompt) = queue.update { it + prompt }

    private fun nameOf(extension: WebExtension) =
        extension.getMetadata()?.name?.ifBlank { null } ?: store.state.extensions[extension.id]?.name ?: extension.id

    /** Permissions and sites in the user's words, as Firefox puts them. */
    private fun describe(permissions: List<String>, origins: List<String>, forUpdate: Boolean) =
        Addon.localizePermissions(permissions + origins, context, forUpdate)

    /** What the developer says the extension collects, or null when it's nothing. */
    private fun describeDataCollection(permissions: List<String>): String? =
        Addon.localizeDataCollectionPermissions(permissions - "none", context)
            .takeIf { it.isNotEmpty() }
            ?.let(Addon::formatLocalizedDataCollectionPermissions)

    private fun failureReason(error: WebExtensionInstallException) = when (error) {
        is WebExtensionInstallException.NetworkFailure -> R.string.extension_failed_network
        is WebExtensionInstallException.CorruptFile -> R.string.extension_failed_corrupt
        is WebExtensionInstallException.NotSigned -> R.string.extension_failed_not_signed
        is WebExtensionInstallException.Incompatible -> R.string.extension_failed_incompatible
        is WebExtensionInstallException.Blocklisted, is WebExtensionInstallException.SoftBlocked -> R.string.extension_failed_blocked
        is WebExtensionInstallException.UnsupportedAddonType -> R.string.extension_failed_unsupported
        is WebExtensionInstallException.AdminInstallOnly -> R.string.extension_failed_admin
        else -> R.string.extension_failed_unknown
    }

    /**
     * Each extension gets a daily update check from when it's installed until it's removed. With
     * [Settings.updateExtensions] off, none are checked.
     */
    private suspend fun scheduleUpdates() {
        var known = emptySet<String>()
        store.flow()
            .map { state -> state.extensions.values.filterNot { it.isBuiltIn }.map { it.id }.toSet() }
            .distinctUntilChanged()
            .collect { ids ->
                (known - ids).forEach(updater::unregisterForFutureUpdates)
                // Scheduling is idempotent: a check that's already scheduled keeps its time.
                if (settings.updateExtensions) ids.forEach(updater::registerForFutureUpdates) else ids.forEach(updater::unregisterForFutureUpdates)
                known = ids
            }
    }

    /** [Settings.updateExtensions] changed: every extension's daily check starts or stops now. */
    fun updateSettingChanged() {
        val ids = store.state.extensions.values.filterNot { it.isBuiltIn }.map { it.id }
        if (settings.updateExtensions) ids.forEach(updater::registerForFutureUpdates) else ids.forEach(updater::unregisterForFutureUpdates)
    }

    /** For testing: checks every extension for an update now. */
    fun updateNow() = store.state.extensions.values.filterNot { it.isBuiltIn }.forEach { updater.update(it.id) }

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

    /** Ensō doesn't list featured add-ons (the user browses the site), so the add-on manager only updates. */
    private object NoFeaturedAddons : AddonsProvider {
        override suspend fun getFeaturedAddons(allowCache: Boolean, readTimeoutInSeconds: Long?, language: String?) = emptyList<Addon>()

        override suspend fun getAddonByID(id: String, readTimeoutInSeconds: Long?, language: String?): Addon? = null
    }

    companion object {
        private const val TAG = "Extensions"

        /** Mozilla's add-on site for Firefox for Android, which lists the extensions that work on Android. */
        const val ADDONS_SITE_URL = "https://addons.mozilla.org/android/"
        const val UBLOCK_ORIGIN_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_ORIGIN_ASSET = "extensions/ublock_origin.xpi"
    }
}
