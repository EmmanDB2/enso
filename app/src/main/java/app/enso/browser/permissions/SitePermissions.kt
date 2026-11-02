package app.enso.browser.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.enso.browser.settings.SiteChoices
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest

/** What a site can ask for and Ensō remembers per site (step 11). Protected content is DRM video. */
enum class SitePermission(val key: String) {
    Camera("camera"),
    Microphone("microphone"),
    Location("location"),
    Notifications("notifications"),
    ProtectedContent("drm"),
}

/** The user's answer for a site: ask next time, or always allow or block. */
enum class PermissionChoice { Ask, Allow, Block }

/** A site's question on screen: what it wants, for which tab. */
data class PermissionPrompt(val tabId: String, val host: String, val permissions: List<SitePermission>, internal val request: PermissionRequest, internal val app: Boolean)

/**
 * Answers sites' permission requests (step 11), for the tab on screen.
 *
 * Gecko sends two kinds. An app request comes first when Ensō lacks the Android permission a site needs (camera,
 * microphone, location); a content request asks for the site itself. Either way the user is asked once per site, in
 * Ensō's card ([prompt]), and the answer is kept in [SiteChoices]. Allowing asks Android for its permission too when
 * it's missing ([requestAndroid]); if Android says no, the site is refused.
 *
 * Without a card: autoplay without sound plays and with sound waits for a tap; persistent storage, cross-site storage
 * and anything else Gecko asks are refused, as Firefox does by default. A blocked site is refused without a word.
 */
class SitePermissions(
    private val context: Context,
    private val store: BrowserStore,
    private val choices: SiteChoices,
    /** Asks Android for these permissions, then reports whether all were granted. */
    private val requestAndroid: (List<String>, (Boolean) -> Unit) -> Unit,
) {
    /** The question showing, if any. */
    var prompt by mutableStateOf<PermissionPrompt?>(null)
        private set

    /** Bumped when a site's choices change, so site settings read them again. */
    var revision by mutableIntStateOf(0)
        private set

    /** The request being dealt with (shown, or waiting on Android), so the store's updates don't handle it twice. */
    private var handling: PermissionRequest? = null

    /**
     * The last requests answered. A store update from before the answer reached the store can still bring one back,
     * and Gecko mustn't be answered twice.
     */
    private val answered = ArrayDeque<PermissionRequest>()

    /**
     * What the user just turned away without answering, from an app request. Gecko follows a refused app request with
     * the site's own request for the same thing, which is refused too rather than asked again.
     */
    private var dismissed: Pair<String, List<SitePermission>>? = null

    /** Looks at the tab on screen's requests: answers what it can, and shows the first that needs the user. */
    fun onState(state: BrowserState) {
        val tab = state.selectedTab
        val showing = handling
        if (showing != null) {
            val stillThere = tab != null && (showing in tab.content.appPermissionRequestsList || showing in tab.content.permissionRequestsList)
            if (stillThere) return
            // The page went away or the tab changed: the question goes with it, to come back when its tab does.
            handling = null
            prompt = null
        }
        tab ?: return
        val (request, app) = tab.content.appPermissionRequestsList.firstOrNull { it !in answered }?.let { it to true }
            ?: tab.content.permissionRequestsList.firstOrNull { it !in answered }?.let { it to false }
            ?: return
        handle(tab.id, tab.content.url, request, app)
    }

    private fun handle(tabId: String, pageUrl: String, request: PermissionRequest, app: Boolean) {
        val host = SiteChoices.hostOf(request.uri ?: pageUrl)
        val kinds = request.permissions.mapNotNull(::kindOf).distinct()
        if (kinds.isEmpty() || host == null) {
            // Nothing to ask about: autoplay without sound plays, and everything else is refused.
            if (!request.grantIf { it is Permission.ContentAutoPlayInaudible }) request.reject()
            remember(request)
            consume(tabId, request, app)
            return
        }
        val stored = kinds.map { choices.permission(host, it) }
        handling = request
        val turnedAway = dismissed
        dismissed = null
        if (!app && turnedAway != null && turnedAway.first == host && turnedAway.second.containsAll(kinds)) {
            finish(tabId, request, app, allow = false)
            return
        }
        when {
            PermissionChoice.Block in stored -> finish(tabId, request, app, allow = false)
            stored.all { it == PermissionChoice.Allow } -> allow(tabId, request, app, kinds)
            else -> prompt = PermissionPrompt(tabId, host, kinds, request, app)
        }
    }

    /** The user's answer to [prompt], remembered for the site. */
    fun answer(allow: Boolean) {
        val shown = prompt ?: return
        prompt = null
        shown.permissions.forEach { choices.setPermission(shown.host, it, if (allow) PermissionChoice.Allow else PermissionChoice.Block) }
        revision++
        if (allow) allow(shown.tabId, shown.request, shown.app, shown.permissions) else finish(shown.tabId, shown.request, shown.app, allow = false)
    }

    /** Back or a tap outside the card: refused this time, and asked again next time. */
    fun dismiss() {
        val shown = prompt ?: return
        prompt = null
        if (shown.app) dismissed = shown.host to shown.permissions
        finish(shown.tabId, shown.request, shown.app, allow = false)
    }

    /** Grants [request] once Android has granted what it needs, asking for anything missing first. */
    private fun allow(tabId: String, request: PermissionRequest, app: Boolean, kinds: List<SitePermission>) {
        val missing = androidPermissionsFor(kinds).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            finish(tabId, request, app, allow = true)
        } else {
            requestAndroid(missing) { granted -> finish(tabId, request, app, allow = granted) }
        }
    }

    private fun finish(tabId: String, request: PermissionRequest, app: Boolean, allow: Boolean) {
        if (allow) request.grant() else request.reject()
        remember(request)
        consume(tabId, request, app)
        if (handling == request) handling = null
    }

    private fun remember(request: PermissionRequest) {
        answered.addLast(request)
        if (answered.size > 16) answered.removeFirst()
    }

    private fun consume(tabId: String, request: PermissionRequest, app: Boolean) {
        store.dispatch(
            if (app) ContentAction.ConsumeAppPermissionsRequest(tabId, request) else ContentAction.ConsumePermissionsRequest(tabId, request),
        )
    }

    /** A site's choices for its Permissions group: the four always, protected content once it has asked. */
    fun choicesFor(host: String): List<Pair<SitePermission, PermissionChoice>> =
        SitePermission.entries.map { it to choices.permission(host, it) }
            .filter { (kind, choice) -> kind != SitePermission.ProtectedContent || choice != PermissionChoice.Ask }

    fun setChoice(host: String, kind: SitePermission, choice: PermissionChoice) {
        choices.setPermission(host, kind, choice)
        revision++
    }

    fun forget(host: String) {
        choices.clearPermissions(host)
        revision++
    }

    companion object {
        /** Which of Ensō's permissions an engine permission is, or null for the ones answered without asking. */
        fun kindOf(permission: Permission): SitePermission? = when (permission) {
            is Permission.ContentVideoCamera, is Permission.ContentVideoCapture, is Permission.AppCamera -> SitePermission.Camera
            is Permission.ContentAudioMicrophone, is Permission.ContentAudioCapture, is Permission.AppAudio -> SitePermission.Microphone
            is Permission.ContentGeoLocation, is Permission.AppLocationCoarse, is Permission.AppLocationFine -> SitePermission.Location
            is Permission.ContentNotification -> SitePermission.Notifications
            is Permission.ContentMediaKeySystemAccess -> SitePermission.ProtectedContent
            else -> null
        }

        /** The Android permissions a site's [kinds] need Ensō to hold. */
        fun androidPermissionsFor(kinds: List<SitePermission>): List<String> = kinds.flatMap { kind ->
            when (kind) {
                SitePermission.Camera -> listOf(Manifest.permission.CAMERA)
                SitePermission.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
                SitePermission.Location -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                SitePermission.Notifications ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                SitePermission.ProtectedContent -> emptyList()
            }
        }
    }
}
