package app.enso.browser.sync

import android.content.Context
import android.util.Log
import app.enso.browser.Settings
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.SpacesStore
import app.enso.browser.spaces.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.Profile
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import java.io.IOException

/** What the Zen sync sheet shows. */
sealed interface ZenSyncStatus {
    data object Starting : ZenSyncStatus

    data object SignedOut : ZenSyncStatus

    /** [refused] is a recent sync that held too many deletions to send, for the sheet to explain. */
    data class SignedIn(val email: String?, val state: State, val refused: RefusedDeletions? = null) : ZenSyncStatus

    sealed interface State {
        data object Syncing : State

        /** [at] is when the last sync finished, or null before the first one. */
        data class Synced(val at: Long?) : State

        /** Zen's spaces sync isn't on for this account. */
        data object ZenSyncOff : State

        /** Zen writes a newer version of the spaces collection than this Ensō reads. */
        data object NeedsUpdate : State

        /** The account needs signing in again. */
        data object NeedsSignIn : State

        data class Failed(val reason: String) : State
    }
}

/**
 * Syncs Zen's spaces both ways through the user's Mozilla account: signs in with [accountManager], syncs Zen's
 * "spaces" collection, and applies the result to the phone with [tabs].
 */
class ZenSync internal constructor(
    context: Context,
    private val accountManager: FxaAccountManager,
    http: SyncHttp,
    private val spaces: SpacesStore,
    private val tabs: TabManager,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mirrorStore = ZenMirrorStore(context)
    private val engine = ZenSpacesEngine(http, mirrorStore, ZenSnapshotStore(context))
    private val running = Mutex()

    /** Counts the user's edits, so a sync can tell whether the phone changed while it ran. */
    private var edits = 0
    private var pushJob: Job? = null

    /** The last sync the deletion guard stopped, as the mirror noted it. */
    private var refused: RefusedDeletions? = null

    private val round = ZenSyncRound(
        engine,
        object : SyncedModel {
            override val state: SpacesState get() = spaces.state.value
            override val edits: Int get() = this@ZenSync.edits
            override fun apply(transform: (SpacesState) -> SpacesState) = tabs.applySpaces(transform)
            override fun flush() = spaces.flush()
        },
        Dispatchers.IO,
    )

    private val _status = MutableStateFlow<ZenSyncStatus>(ZenSyncStatus.Starting)
    val status: StateFlow<ZenSyncStatus> = _status.asStateFlow()

    private val _icons = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Synced tabs' icons from Zen, by tab id, so they show before the tab loads. They stay in the mirror, not the
     * spaces model: together they're many times the size of spaces.json.
     */
    val icons: StateFlow<Map<String, String>> = _icons.asStateFlow()

    private val observer = object : AccountObserver {
        override fun onReady(authenticatedAccount: OAuthAccount?) {
            scope.launch {
                if (authenticatedAccount == null) {
                    _status.value = ZenSyncStatus.SignedOut
                } else {
                    val mirror = withContext(Dispatchers.IO) { mirrorStore.load() }
                    refused = mirror.refused
                    setState(ZenSyncStatus.State.Synced(mirror.lastSynced))
                    syncNow()
                }
            }
        }

        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            scope.launch {
                setState(ZenSyncStatus.State.Synced(at = null))
                syncNow()
            }
        }

        override fun onProfileUpdated(profile: Profile) {
            scope.launch {
                val current = _status.value as? ZenSyncStatus.SignedIn ?: return@launch
                _status.value = current.copy(email = profile.email)
            }
        }

        override fun onAuthenticationProblems() {
            scope.launch { setState(ZenSyncStatus.State.NeedsSignIn) }
        }

        override fun onLoggedOut() {
            scope.launch {
                engine.reset()
                refused = null
                _icons.value = emptyMap()
                _status.value = ZenSyncStatus.SignedOut
            }
        }
    }

    fun start() {
        accountManager.register(observer)
        scope.launch { accountManager.start() }
        scope.launch {
            val icons = withContext(Dispatchers.IO) { mirrorStore.load().tabIcons() }
            // A sync that finished first has newer ones.
            if (_icons.value.isEmpty()) _icons.value = icons
        }
    }

    /** The URL of Mozilla's sign-in page, to open in a tab. */
    suspend fun beginSignIn(): String? = accountManager.beginAuthentication(entrypoint = MozillaAccount.EntryPoint)

    fun signOut() {
        scope.launch { accountManager.logout() }
    }

    /** The user changed spaces, tabs or folders: send it to Zen shortly, once the edits settle. */
    fun localEdited() {
        edits++
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            syncNow()
        }
    }

    /**
     * Debug builds only: puts Zen's collection back as it was before the phone's first write, or as the kept
     * snapshot called [name] has it.
     */
    fun restoreSnapshot(name: String? = null) {
        val account = accountManager.authenticatedAccount() ?: return
        scope.launch {
            running.lock()
            try {
                val auth = authOf(account) ?: return@launch
                withContext(Dispatchers.IO) { engine.restoreSnapshot(auth, name) }
            } catch (e: Exception) {
                Log.e(TAG, "Restoring the snapshot failed", e)
            } finally {
                running.unlock()
            }
            syncNow()
        }
    }

    /** Debug builds only: replaces the snapshot with Zen's collection as it is now, so a restore puts this back. */
    fun saveSnapshot() {
        val account = accountManager.authenticatedAccount() ?: return
        scope.launch {
            running.lock()
            try {
                val auth = authOf(account) ?: return@launch
                withContext(Dispatchers.IO) { engine.saveSnapshot(auth) }
            } catch (e: Exception) {
                Log.e(TAG, "Saving a snapshot failed", e)
            } finally {
                running.unlock()
            }
        }
    }

    /**
     * Asks the server whether Zen changed anything since the last sync, and syncs if it did. Cheap: one small
     * request, so the app calls it every few seconds while it's open ([Settings.zenSyncCheckSeconds]).
     */
    fun checkForChanges() {
        val account = accountManager.authenticatedAccount() ?: return
        if (running.isLocked || System.currentTimeMillis() < engine.backoffUntil) return
        if ((_status.value as? ZenSyncStatus.SignedIn)?.state?.allowsQuickCheck() == false) return
        scope.launch {
            val changed = try {
                val auth = authOf(account) ?: return@launch
                withContext(Dispatchers.IO) { engine.serverChanged(auth) }
            } catch (e: Exception) {
                // The next check or the next sync tries again; a real problem shows there.
                Log.d(TAG, "Couldn't check for Zen changes: ${e.message}")
                false
            }
            if (changed) {
                Log.i(TAG, "Zen changed the spaces; syncing")
                syncNow()
            }
        }
    }

    /** Syncs unless signed out, already syncing, or [minIntervalMs] hasn't passed since the last sync. */
    fun syncNow(minIntervalMs: Long = 0) {
        val account = accountManager.authenticatedAccount() ?: return
        val now = System.currentTimeMillis()
        if (now < engine.backoffUntil) return
        val lastSynced = ((_status.value as? ZenSyncStatus.SignedIn)?.state as? ZenSyncStatus.State.Synced)?.at
        if (lastSynced != null && now - lastSynced < minIntervalMs) return
        scope.launch {
            if (!running.tryLock()) return@launch
            try {
                setState(ZenSyncStatus.State.Syncing)
                val auth = authOf(account) ?: return@launch setState(ZenSyncStatus.State.NeedsSignIn)
                val options = SyncOptions(settings.syncOpenTabs, settings.syncTabNavigations, settings.zenSyncDryRun)
                val recordId = settings.syncDeviceRecordId
                val device = withContext(Dispatchers.IO) { syncDevice(account, recordId) }
                val outcome = round.run(auth, device, options)
                val state = when (val result = outcome.result) {
                    is ZenSyncResult.Synced -> {
                        _icons.value = result.mirror.tabIcons()
                        refused = result.mirror.refused
                        ZenSyncStatus.State.Synced(result.mirror.lastSynced)
                    }
                    ZenSyncResult.NotSetUp, ZenSyncResult.NotTurnedOn -> ZenSyncStatus.State.ZenSyncOff
                    is ZenSyncResult.NeedsUpdate -> ZenSyncStatus.State.NeedsUpdate
                }
                // The phone still has changes to send (see ZenSyncRound): an edit made meanwhile, or whatever its
                // model still holds that the server doesn't.
                if (outcome.again) localEdited()
                setState(state)
            } catch (e: SyncConflictException) {
                // Zen wrote while the phone was uploading; download that first, then try again.
                Log.i(TAG, "Zen changed the collection during the sync; syncing again")
                setState(ZenSyncStatus.State.Synced(mirrorStore.load().lastSynced))
                localEdited()
            } catch (e: SyncAuthException) {
                Log.w(TAG, "Sync refused the account", e)
                setState(ZenSyncStatus.State.NeedsSignIn)
            } catch (e: IOException) {
                Log.w(TAG, "Sync failed", e)
                setState(ZenSyncStatus.State.Failed(e.message ?: "Network error"))
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                setState(ZenSyncStatus.State.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                running.unlock()
            }
        }
    }

    /** This phone's Sync device record, or null before the account has set up its device. */
    private fun syncDevice(account: OAuthAccount, recordId: String): SyncDevice? {
        val fxaDeviceId = account.getCurrentDeviceId() ?: return null
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull().orEmpty()
        return SyncDevice(recordId, fxaDeviceId, MozillaAccount.deviceName(appContext), version, appContext.packageName)
    }

    private suspend fun authOf(account: OAuthAccount): SyncAuth? {
        val token = account.getAccessToken(SCOPE_SYNC) ?: return null
        val key = token.key ?: return null
        val tokenServer = account.getTokenServerEndpointURL() ?: return null
        return SyncAuth(kid = key.kid, accessToken = token.token, syncKey = key.k, tokenServerUrl = tokenServer)
    }

    private fun setState(state: ZenSyncStatus.State) {
        val email = accountManager.accountProfile()?.email ?: (_status.value as? ZenSyncStatus.SignedIn)?.email
        val recentlyRefused = refused?.takeIf { System.currentTimeMillis() - it.at < REFUSED_NOTE_MS }
        _status.value = ZenSyncStatus.SignedIn(email, state, recentlyRefused)
    }

    private companion object {
        const val TAG = "ZenSync"
        const val PUSH_DELAY_MS = 3_000L

        /** How long the sync sheet keeps saying that a sync was refused for its deletions. */
        const val REFUSED_NOTE_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Whether the half-minute check for Zen's changes ([ZenSync.checkForChanges]) runs in this state. It's a full sync
 * whenever the phone has nothing to compare the server with, so it waits where a sync can't get anywhere by itself:
 * the account needs signing in, Ensō needs an update, Zen's spaces sync is off, or the last sync failed. The sync
 * every few minutes still tries, and the check carries on once one gets through.
 */
internal fun ZenSyncStatus.State.allowsQuickCheck(): Boolean = when (this) {
    ZenSyncStatus.State.NeedsSignIn, ZenSyncStatus.State.NeedsUpdate, ZenSyncStatus.State.ZenSyncOff -> false
    is ZenSyncStatus.State.Failed -> false
    ZenSyncStatus.State.Syncing, is ZenSyncStatus.State.Synced -> true
}
