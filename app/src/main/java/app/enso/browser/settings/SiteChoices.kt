package app.enso.browser.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/**
 * What the user picked for particular sites in their site settings (step 10): for now, desktop or mobile. A site
 * without a choice follows the screen. Kept by host, in a prefs file of their own.
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

    companion object {
        /** A page's host, which choices are kept by; null for pages without one (about:, data:). */
        fun hostOf(url: String?): String? = url?.let { Uri.parse(it).host }?.ifEmpty { null }
    }
}
