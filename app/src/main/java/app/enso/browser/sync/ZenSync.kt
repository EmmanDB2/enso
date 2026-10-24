package app.enso.browser.sync

import android.content.Context
import android.util.Log
import app.enso.browser.spaces.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    data class SignedIn(val email: String?, val state: State) : ZenSyncStatus

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
 * Syncs Zen's spaces to the phone (read-only for now) through the user's Mozilla account: signs in with
 * [accountManager], reads Zen's "spaces" collection, and applies it with [tabs].
 */
class ZenSync internal constructor(
    context: Context,
    private val accountManager: FxaAccountManager,
    http: SyncHttp,
    private val tabs: TabManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mirrorStore = ZenMirrorStore(context)
    private val reader = ZenSpacesReader(http, mirrorStore)
    private val running = Mutex()

    private val _status = MutableStateFlow<ZenSyncStatus>(ZenSyncStatus.Starting)
    val status: StateFlow<ZenSyncStatus> = _status.asStateFlow()

    private val observer = object : AccountObserver {
        override fun onReady(authenticatedAccount: OAuthAccount?) {
            scope.launch {
                if (authenticatedAccount == null) {
                    _status.value = ZenSyncStatus.SignedOut
                } else {
                    setState(ZenSyncStatus.State.Synced(mirrorStore.load().lastSynced))
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
                reader.reset()
                _status.value = ZenSyncStatus.SignedOut
            }
        }
    }

    fun start() {
        accountManager.register(observer)
        scope.launch { accountManager.start() }
    }

    /** The URL of Mozilla's sign-in page, to open in a tab. */
    suspend fun beginSignIn(): String? = accountManager.beginAuthentication(entrypoint = MozillaAccount.EntryPoint)

    fun signOut() {
        scope.launch { accountManager.logout() }
    }

    /** Syncs unless signed out, already syncing, or [minIntervalMs] hasn't passed since the last sync. */
    fun syncNow(minIntervalMs: Long = 0) {
        val account = accountManager.authenticatedAccount() ?: return
        val now = System.currentTimeMillis()
        if (now < reader.backoffUntil) return
        val lastSynced = ((_status.value as? ZenSyncStatus.SignedIn)?.state as? ZenSyncStatus.State.Synced)?.at
        if (lastSynced != null && now - lastSynced < minIntervalMs) return
        scope.launch {
            if (!running.tryLock()) return@launch
            try {
                setState(ZenSyncStatus.State.Syncing)
                val auth = authOf(account) ?: return@launch setState(ZenSyncStatus.State.NeedsSignIn)
                val state = when (val result = withContext(Dispatchers.IO) { reader.read(auth) }) {
                    is ZenReadResult.Synced -> {
                        tabs.applySpaces { it.withZenSpaces(result.mirror) }
                        ZenSyncStatus.State.Synced(result.mirror.lastSynced)
                    }
                    ZenReadResult.NotSetUp, ZenReadResult.NotTurnedOn -> ZenSyncStatus.State.ZenSyncOff
                    is ZenReadResult.NeedsUpdate -> ZenSyncStatus.State.NeedsUpdate
                }
                setState(state)
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

    private suspend fun authOf(account: OAuthAccount): SyncAuth? {
        val token = account.getAccessToken(SCOPE_SYNC) ?: return null
        val key = token.key ?: return null
        val tokenServer = account.getTokenServerEndpointURL() ?: return null
        return SyncAuth(kid = key.kid, accessToken = token.token, syncKey = key.k, tokenServerUrl = tokenServer)
    }

    private fun setState(state: ZenSyncStatus.State) {
        val email = accountManager.accountProfile()?.email ?: (_status.value as? ZenSyncStatus.SignedIn)?.email
        _status.value = ZenSyncStatus.SignedIn(email, state)
    }

    private companion object {
        const val TAG = "ZenSync"
    }
}
