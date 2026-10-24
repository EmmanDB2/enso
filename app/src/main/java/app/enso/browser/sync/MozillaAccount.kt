package app.enso.browser.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.service.fxa.ServerConfig
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.SCOPE_SYNC

/** How Ensō signs in to a Mozilla account. */
object MozillaAccount {
    /**
     * Firefox for Android's OAuth client, reused for now: Mozilla only gives Sync access to its own apps, and Fenix
     * forks like Dejavu sign in this way. Replace it with Ensō's own client before publishing (see CLAUDE.md).
     */
    private const val CLIENT_ID = "a2270f727f45f648"
    private const val REDIRECT_URL = "urn:ietf:wg:oauth:2.0:oob:oauth-redirect-webchannel"

    val serverConfig: ServerConfig = ServerConfig(FxaServer.Release, CLIENT_ID, REDIRECT_URL)

    /**
     * Only the Sync scope, for Zen's spaces collection. No [mozilla.components.service.fxa.SyncConfig], so
     * Firefox's own engines (history, bookmarks, passwords, cards) never sync.
     */
    fun accountManager(context: Context) = FxaAccountManager(
        context = context,
        serverConfig = serverConfig,
        deviceConfig = DeviceConfig(name = deviceName(context), type = DeviceType.MOBILE, capabilities = emptySet()),
        syncConfig = null,
        applicationScopes = setOf(SCOPE_SYNC),
    )

    /** Where sign-in starts, for Mozilla's metrics. */
    object EntryPoint : FxAEntryPoint {
        override val entryName = "enso-zen-sync"
    }

    private fun deviceName(context: Context): String {
        val device = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        return "Ensō on $device"
    }
}
