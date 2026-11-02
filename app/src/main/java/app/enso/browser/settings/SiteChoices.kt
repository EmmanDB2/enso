package app.enso.browser.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import app.enso.browser.permissions.PermissionChoice
import app.enso.browser.permissions.SitePermission

/**
 * What the user picked for particular sites in their site settings: desktop or mobile (step 10; a site without a
 * choice follows the screen), and their answers to its permission requests (step 11). Kept by host, in a prefs file of
 * their own.
 */
class SiteChoices(context: Context) {
    private val prefs = context.getSharedPreferences("enso_sites", Context.MODE_PRIVATE)

    /** Desktop (true) or mobile (false) for [host], or null to follow the screen. */
    fun desktop(host: String?): Boolean? {
        val key = host?.let(::desktopKey) ?: return null
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setDesktop(host: String, desktop: Boolean?) = prefs.edit {
        if (desktop == null) remove(desktopKey(host)) else putBoolean(desktopKey(host), desktop)
    }

    private fun desktopKey(host: String) = "desktop:$host"

    /** What the user answered [host] about [permission] (step 11): Ask until they answer. */
    fun permission(host: String, permission: SitePermission): PermissionChoice =
        prefs.getString(permissionKey(host, permission), null)?.let { saved -> PermissionChoice.entries.firstOrNull { it.name == saved } }
            ?: PermissionChoice.Ask

    fun setPermission(host: String, permission: SitePermission, choice: PermissionChoice) = prefs.edit {
        if (choice == PermissionChoice.Ask) remove(permissionKey(host, permission)) else putString(permissionKey(host, permission), choice.name)
    }

    /** Forgets every permission answer for [host], as clearing its data does. */
    fun clearPermissions(host: String) = prefs.edit { SitePermission.entries.forEach { remove(permissionKey(host, it)) } }

    private fun permissionKey(host: String, permission: SitePermission) = "permission:${permission.key}:$host"

    companion object {
        /** A page's host, which choices are kept by; null for pages without one (about:, data:). */
        fun hostOf(url: String?): String? = url?.let { Uri.parse(it).host }?.ifEmpty { null }
    }
}
