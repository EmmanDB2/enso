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

/** Which layout the unfolded screen uses while it's held in portrait. */
enum class UnfoldedPortraitLayout {
    /** The sidebar or rail beside the page card, as in landscape (boards 5 and 6). */
    Desktop,

    /** The cover's layout: the top bar over the page (boards 1–4). */
    Mobile,
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("enso_settings", Context.MODE_PRIVATE)

    // User preferences. None are in a settings UI yet; they're meant to become settings later.

    /** Hide the Android status bar together with the top bar, and on the inner screen. */
    var hideStatusBarWithTopBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }

    var tabSwipeScope: TabSwipeScope
        get() = prefs.getString(KEY_TAB_SWIPE_SCOPE, null)
            ?.let { name -> TabSwipeScope.entries.firstOrNull { it.name == name } }
            ?: TabSwipeScope.OpenTabs
        set(value) = prefs.edit { putString(KEY_TAB_SWIPE_SCOPE, value.name) }

    /** Swipe the tab sheet's header sideways to switch spaces, as well as the space menu. */
    var switchSpacesFromSheetHeader: Boolean
        get() = prefs.getBoolean(KEY_SWITCH_SPACES_FROM_SHEET_HEADER, false)
        set(value) = prefs.edit { putBoolean(KEY_SWITCH_SPACES_FROM_SHEET_HEADER, value) }

    /** How long to hold the edge or the address field before the space menu opens (spec: 300–500 ms). */
    var spaceMenuHoldMs: Long
        get() = prefs.getLong(KEY_SPACE_MENU_HOLD_MS, 300L)
        set(value) = prefs.edit { putLong(KEY_SPACE_MENU_HOLD_MS, value) }

    /**
     * How much of the screen's height, from the bottom up, the space menu's edge strip covers. It never reaches
     * into the top bar; the default runs the whole edge below it.
     */
    var spaceMenuStripHeight: Float
        get() = prefs.getFloat(KEY_SPACE_MENU_STRIP_HEIGHT, 1f)
        set(value) = prefs.edit { putFloat(KEY_SPACE_MENU_STRIP_HEIGHT, value) }

    /** The unfolded screen's layout in portrait. Landscape always uses the sidebar. */
    var unfoldedPortraitLayout: UnfoldedPortraitLayout
        get() = prefs.getString(KEY_UNFOLDED_PORTRAIT_LAYOUT, null)
            ?.let { name -> UnfoldedPortraitLayout.entries.firstOrNull { it.name == name } }
            ?: UnfoldedPortraitLayout.Mobile
        set(value) = prefs.edit { putString(KEY_UNFOLDED_PORTRAIT_LAYOUT, value.name) }

    /**
     * The thin border of the space's colors around the inner screen's page card. Off, the page sits flush beside
     * the sidebar with square corners.
     */
    var innerPageBorder: Boolean
        get() = prefs.getBoolean(KEY_INNER_PAGE_BORDER, true)
        set(value) = prefs.edit { putBoolean(KEY_INNER_PAGE_BORDER, value) }

    /** The inner screen's full sidebar width in dp, set by dragging its edge. */
    var innerSidebarWidthDp: Float
        get() = prefs.getFloat(KEY_INNER_SIDEBAR_WIDTH, DEFAULT_SIDEBAR_WIDTH_DP)
        set(value) = prefs.edit { putFloat(KEY_INNER_SIDEBAR_WIDTH, value) }

    /** Left-handed mode: the space menu's wave and its trigger strip move to the left edge. */
    var spaceMenuLeftHanded: Boolean
        get() = prefs.getBoolean(KEY_SPACE_MENU_LEFT_HANDED, false)
        set(value) = prefs.edit { putBoolean(KEY_SPACE_MENU_LEFT_HANDED, value) }

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

    /** Whether the inner screen shows the full sidebar rather than the rail (board 5 is the default). */
    var innerSidebarExpanded: Boolean
        get() = prefs.getBoolean(KEY_INNER_SIDEBAR_EXPANDED, false)
        set(value) = prefs.edit { putBoolean(KEY_INNER_SIDEBAR_EXPANDED, value) }

    /** When the app last went to the background. */
    var lastStoppedAt: Long?
        get() = prefs.getLong(KEY_LAST_STOPPED_AT, 0L).takeIf { it > 0 }
        set(value) = prefs.edit { putLong(KEY_LAST_STOPPED_AT, value ?: 0L) }

    private companion object {
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar_with_top_bar"
        const val KEY_TAB_SWIPE_SCOPE = "tab_swipe_scope"
        const val KEY_SWITCH_SPACES_FROM_SHEET_HEADER = "switch_spaces_from_sheet_header"
        const val KEY_SPACE_MENU_HOLD_MS = "space_menu_hold_ms"
        const val KEY_SPACE_MENU_LEFT_HANDED = "space_menu_left_handed"
        const val KEY_UNFOLDED_PORTRAIT_LAYOUT = "unfolded_portrait_layout"
        const val KEY_INNER_PAGE_BORDER = "inner_page_border"
        const val KEY_INNER_SIDEBAR_WIDTH = "inner_sidebar_width_dp"

        /** The width the user settled on by dragging. */
        const val DEFAULT_SIDEBAR_WIDTH_DP = 240f
        const val KEY_SPACE_MENU_STRIP_HEIGHT = "space_menu_strip_height"
        const val KEY_ESSENTIALS_PER_CONTAINER = "essentials_per_container"
        const val KEY_SYNC_OPEN_TABS = "sync_open_tabs"
        const val KEY_SYNC_TAB_NAVIGATIONS = "sync_tab_navigations"
        const val KEY_ZEN_SYNC_DRY_RUN = "zen_sync_dry_run"
        const val KEY_QUICK_TAB_LIFETIME_HOURS = "quick_tab_lifetime_hours"
        const val KEY_START_SCREEN_AFTER_MINUTES = "start_screen_after_minutes"
        const val KEY_LAST_STOPPED_AT = "last_stopped_at"
        const val KEY_INNER_SIDEBAR_EXPANDED = "inner_sidebar_expanded"
    }
}
