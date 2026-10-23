package app.enso.browser

import android.content.Context
import androidx.core.content.edit

/** Which tabs a sideways swipe on the address field moves through. */
enum class TabSwipeScope {
    /** Open tabs only. */
    OpenTabs,

    /** Loaded Essentials and pinned tabs, then open tabs, in tab-sheet order. */
    AllTabs,
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("enso_settings", Context.MODE_PRIVATE)

    /** Hide the Android status bar together with the top bar. Not in the UI yet. */
    var hideStatusBarWithTopBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }

    /** Not in the UI yet. */
    var tabSwipeScope: TabSwipeScope
        get() = prefs.getString(KEY_TAB_SWIPE_SCOPE, null)
            ?.let { name -> TabSwipeScope.entries.firstOrNull { it.name == name } }
            ?: TabSwipeScope.OpenTabs
        set(value) = prefs.edit { putString(KEY_TAB_SWIPE_SCOPE, value.name) }

    private companion object {
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar_with_top_bar"
        const val KEY_TAB_SWIPE_SCOPE = "tab_swipe_scope"
    }
}
