package app.enso.browser

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/** Which tabs a sideways swipe on the address field moves through. */
enum class TabSwipeScope {
    /** Open tabs only. */
    OpenTabs,

    /** Loaded Essentials and pinned tabs, then open tabs, in tab-sheet order. */
    AllTabs,
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("enso_settings", Context.MODE_PRIVATE)

    // User preferences. None are in a settings UI yet; they're meant to become settings later.

    /** Hide the Android status bar together with the top bar. */
    var hideStatusBarWithTopBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }

    var tabSwipeScope: TabSwipeScope
        get() = prefs.getString(KEY_TAB_SWIPE_SCOPE, null)
            ?.let { name -> TabSwipeScope.entries.firstOrNull { it.name == name } }
            ?: TabSwipeScope.OpenTabs
        set(value) = prefs.edit { putString(KEY_TAB_SWIPE_SCOPE, value.name) }

    /** Swipe the tab sheet's header sideways to switch spaces. The way to switch until the arc menu (step 6). */
    var switchSpacesFromSheetHeader: Boolean
        get() = prefs.getBoolean(KEY_SWITCH_SPACES_FROM_SHEET_HEADER, true)
        set(value) = prefs.edit { putBoolean(KEY_SWITCH_SPACES_FROM_SHEET_HEADER, value) }

    /** Each Zen container has its own Essentials, as in Zen; off shows every Essential in every space. */
    var essentialsPerContainer: Boolean
        get() = prefs.getBoolean(KEY_ESSENTIALS_PER_CONTAINER, true)
        set(value) = prefs.edit { putBoolean(KEY_ESSENTIALS_PER_CONTAINER, value) }

    /** Normal open tabs sync both ways with Zen (new, navigated, closed). Quick Tabs never sync. */
    var syncOpenTabs: Boolean
        get() = prefs.getBoolean(KEY_SYNC_OPEN_TABS, true)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_OPEN_TABS, value) }

    /**
     * Navigating a tab that came from Zen moves it on the desktop too. Off by default: phone pages often redirect
     * (to a login page, say) in ways that shouldn't reach the desktop.
     */
    var syncTabNavigations: Boolean
        get() = prefs.getBoolean(KEY_SYNC_TAB_NAVIGATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_TAB_NAVIGATIONS, value) }

    /** For testing: work out and log what Zen sync would upload, without uploading. */
    var zenSyncDryRun: Boolean
        get() = prefs.getBoolean(KEY_ZEN_SYNC_DRY_RUN, false)
        set(value) = prefs.edit { putBoolean(KEY_ZEN_SYNC_DRY_RUN, value) }

    /** How long a Quick Tab lasts after it was last viewed. */
    var quickTabLifetimeHours: Int
        get() = prefs.getInt(KEY_QUICK_TAB_LIFETIME_HOURS, 24)
        set(value) = prefs.edit { putInt(KEY_QUICK_TAB_LIFETIME_HOURS, value) }

    /** How long the app must be away before it reopens on the start screen. */
    var startScreenAfterMinutes: Int
        get() = prefs.getInt(KEY_START_SCREEN_AFTER_MINUTES, 30)
        set(value) = prefs.edit { putInt(KEY_START_SCREEN_AFTER_MINUTES, value) }

    val quickTabLifetimeMs get() = TimeUnit.HOURS.toMillis(quickTabLifetimeHours.toLong())
    val startScreenAfterMs get() = TimeUnit.MINUTES.toMillis(startScreenAfterMinutes.toLong())

    // App state.

    /** When the app last went to the background. */
    var lastStoppedAt: Long?
        get() = prefs.getLong(KEY_LAST_STOPPED_AT, 0L).takeIf { it > 0 }
        set(value) = prefs.edit { putLong(KEY_LAST_STOPPED_AT, value ?: 0L) }

    private companion object {
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar_with_top_bar"
        const val KEY_TAB_SWIPE_SCOPE = "tab_swipe_scope"
        const val KEY_SWITCH_SPACES_FROM_SHEET_HEADER = "switch_spaces_from_sheet_header"
        const val KEY_ESSENTIALS_PER_CONTAINER = "essentials_per_container"
        const val KEY_SYNC_OPEN_TABS = "sync_open_tabs"
        const val KEY_SYNC_TAB_NAVIGATIONS = "sync_tab_navigations"
        const val KEY_ZEN_SYNC_DRY_RUN = "zen_sync_dry_run"
        const val KEY_QUICK_TAB_LIFETIME_HOURS = "quick_tab_lifetime_hours"
        const val KEY_START_SCREEN_AFTER_MINUTES = "start_screen_after_minutes"
        const val KEY_LAST_STOPPED_AT = "last_stopped_at"
    }
}
