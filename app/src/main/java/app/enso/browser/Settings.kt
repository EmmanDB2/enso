package app.enso.browser

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("enso_settings", Context.MODE_PRIVATE)

    /** Hide the Android status bar together with the top bar. Not in the UI yet. */
    var hideStatusBarWithTopBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }

    /** Page to reopen on launch, until the start screen (build step 4) replaces this. */
    var lastUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) = prefs.edit { putString(KEY_LAST_URL, value) }

    private companion object {
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar_with_top_bar"
        const val KEY_LAST_URL = "last_url"
    }
}
