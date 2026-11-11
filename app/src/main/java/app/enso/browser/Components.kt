package app.enso.browser

import mozilla.components.feature.readerview.ReaderViewMiddleware
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.downloads.DownloadMiddleware
import app.enso.browser.downloads.DownloadService
import android.os.Environment
import org.mozilla.geckoview.GeckoSession
import app.enso.browser.permissions.SitePermission
import app.enso.browser.permissions.PermissionChoice
import android.content.Context
import app.enso.browser.extensions.Extensions
import app.enso.browser.settings.SiteChoices
import app.enso.browser.spaces.SpacesStore
import app.enso.browser.spaces.TabManager
import app.enso.browser.sync.FetchSyncHttp
import app.enso.browser.sync.MozillaAccount
import app.enso.browser.sync.ZenSync
import app.enso.browser.ui.FaviconSource
import mozilla.components.browser.engine.gecko.GeckoEngine
import app.enso.browser.toolbar.SearchSuggestions
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.SettingsUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/** App-wide singletons: the engine, the browser state and the actions on it. */
class Components(private val context: Context) {
    // Gecko allows one runtime per process.
    private val runtime by lazy {
        // Pages' default zoom: a lower display density lays pages out with more CSS pixels, so text, images and
        // layout all shrink together. Gecko reads it once, at startup, for both screens (they share a density).
        val density = context.resources.displayMetrics.density * settings.pageZoomPercent / 100f
        GeckoRuntime.create(
            context,
            GeckoRuntimeSettings.Builder()
                .displayDensityOverride(density)
                // The add-on site installs through Firefox's add-on web API (mozAddonManager), as Firefox for Android
                // turns it on. Without it the site falls back to opening the .xpi, which Gecko doesn't install.
                .extensionsWebAPIEnabled(true)
                .build(),
        )
    }

    /** HTTP through Gecko, for application-services and Zen sync. */
    val fetchClient by lazy { GeckoViewFetchClient(context, runtime) }
    val searchSuggestions by lazy { SearchSuggestions(fetchClient) }

    /** Downloads (step 12) save to the phone's Downloads folder. */
    val downloadFileUtils by lazy {
        DefaultDownloadFileUtils(context) { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path }
    }
    val downloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }

    /**
     * Brings Gecko's own stored answer for [url]'s [permission] in line with the user's choice in site settings. Gecko
     * keeps the answers to location, notification and protected-content requests itself, and wouldn't ask again.
     * Camera and microphone answers live only in [siteChoices].
     */
    fun syncGeckoPermission(url: String, permission: SitePermission, choice: PermissionChoice) {
        val gecko = when (permission) {
            SitePermission.Location -> GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION
            SitePermission.Notifications -> GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
            SitePermission.ProtectedContent -> GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
            else -> return
        }
        val value = when (choice) {
            PermissionChoice.Ask -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
            PermissionChoice.Allow -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            PermissionChoice.Block -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        }
        runtime.storageController.getPermissions(url, false).accept { stored ->
            stored.orEmpty().filter { it.permission == gecko }.forEach { runtime.storageController.setPermission(it, value) }
        }
    }

    /** Firefox's Places database; the search palette reads it and step 5 syncs it. */
    val historyStorage by lazy { PlacesHistoryStorage(context) }

    val engine by lazy {
        val engineSettings = DefaultSettings(
            historyTrackingDelegate = HistoryDelegate(lazy { historyStorage }),
            trackingProtectionPolicy = trackingProtectionPolicy(),
        )
        GeckoEngine(context, engineSettings, runtime)
    }

    /** Gecko's Enhanced Tracking Protection at the level in [Settings.trackingProtection]. */
    fun trackingProtectionPolicy(): TrackingProtectionPolicy = when (settings.trackingProtection) {
        TrackingProtectionLevel.Standard -> TrackingProtectionPolicy.recommended()
        TrackingProtectionLevel.Strict -> TrackingProtectionPolicy.strict()
    }

    /** Sites the user let off tracking protection, and changing its level for every tab. */
    val trackingProtection by lazy { TrackingProtectionUseCases(store, engine) }
    val engineSettingsUseCases by lazy { SettingsUseCases(engine, store) }

    /** What the user picked for particular sites in site settings. */
    val siteChoices by lazy { SiteChoices(context) }

    /** Desktop sites with the inner screen's sidebar layout; its middleware runs ahead of the engine's. */
    val desktopSites by lazy { DesktopSites(siteChoices) }

    /** Page thumbnails for the bottom bar's Quick Tabs stack and cards, kept on disk by tab id. */
    val thumbnailStorage by lazy { ThumbnailStorage(context) }

    val store by lazy {
        BrowserStore(
            middleware = listOf(
                desktopSites.middleware,
                ThumbnailsMiddleware(thumbnailStorage),
                // Reader (polish pass): keeps each tab's reader state.
                ReaderViewMiddleware(),
                // Downloads (step 12): starts the download service and keeps the list of downloads.
                // Removing a download from the list keeps its file.
                DownloadMiddleware(context, DownloadService::class.java, deleteFileFromStorage = { false }, downloadFileUtils = downloadFileUtils),
            ) + EngineMiddleware.create(engine),
        )
    }
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val settings by lazy { Settings(context) }
    val spaces by lazy { SpacesStore(context) }
    val tabs by lazy { TabManager(spaces, store, tabsUseCases).also { it.start() } }

    val extensions by lazy { Extensions(context, engine, store, settings) }

    val accountManager by lazy { MozillaAccount.accountManager(context) }
    val zenSync by lazy {
        ZenSync(context, accountManager, FetchSyncHttp(fetchClient), spaces, tabs, settings).also { it.start() }
    }

    /** AC's icon module: pages' icons into the store (once installed), and a cache that fetches the rest. */
    val icons by lazy { BrowserIcons(context, fetchClient) }
    val favicons by lazy { FaviconSource(icons, store, zenSync.icons) }
}
