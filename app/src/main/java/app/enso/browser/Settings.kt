package app.enso.browser

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/** What the cover does with the status bar while you browse. */
enum class CoverStatusBar {
    /**
     * Hidden, so the page reaches the top edge. It shows over the tab sheet, the start screen and the bottom bar's
     * panels, and with a swipe down from the top.
     */
    Hidden,

    /** Always showing, drawn over the page. */
    OverPage,

    /** Over a strip in the space's color, which slides away with the bottom bar and takes the status bar along. */
    TintedStrip,
}

/** How hard Gecko's Enhanced Tracking Protection blocks (step 10). Each site can be let off in its site settings. */
enum class TrackingProtectionLevel { Standard, Strict }

/** Whether Ensō is light or dark (step 10). Pages are asked for the same. */
enum class ThemeMode {
    /** As Android's dark theme is. */
    System,
    Light,
    Dark,
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

    /** Light or dark: Android's dark theme decides, unless this picks one. */
    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.System
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.name) }

    /** How hard tracking protection blocks. */
    var trackingProtection: TrackingProtectionLevel
        get() = prefs.getString(KEY_TRACKING_PROTECTION, null)
            ?.let { name -> TrackingProtectionLevel.entries.firstOrNull { it.name == name } }
            ?: TrackingProtectionLevel.Standard
        set(value) = prefs.edit { putString(KEY_TRACKING_PROTECTION, value.name) }

    /** What the cover does with the status bar while you browse. */
    var coverStatusBar: CoverStatusBar
        get() = prefs.getString(KEY_COVER_STATUS_BAR, null)
            ?.let { name -> CoverStatusBar.entries.firstOrNull { it.name == name } }
            ?: CoverStatusBar.Hidden
        set(value) = prefs.edit { putString(KEY_COVER_STATUS_BAR, value.name) }

    /** Hide the Android status bar on the inner screen, so the page card's border runs to the top. */
    var hideStatusBarUnfolded: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }

    /**
     * The cover's tab sheet lists Quick Tabs among the open tabs too. Off by default: Quick Tabs have their own
     * stack in the bottom bar.
     */
    var quickTabsInTabSheet: Boolean
        get() = prefs.getBoolean(KEY_QUICK_TABS_IN_TAB_SHEET, false)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_TABS_IN_TAB_SHEET, value) }

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

    /** Desktop sites while the inner screen shows the sidebar layout. Pages switch the next time they load. */
    var desktopSitesUnfolded: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_SITES_UNFOLDED, true)
        set(value) = prefs.edit { putBoolean(KEY_DESKTOP_SITES_UNFOLDED, value) }

    /**
     * Desktop sites for tabs in a split too. Off by default: each page of a split is about as narrow as the
     * cover, so desktop sites come out tiny there.
     */
    var desktopSitesInSplit: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_SITES_IN_SPLIT, false)
        set(value) = prefs.edit { putBoolean(KEY_DESKTOP_SITES_IN_SPLIT, value) }

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

    /** Checks extensions for updates once a day and installs them. An update that wants more permissions asks first. */
    var updateExtensions: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_EXTENSIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_UPDATE_EXTENSIONS, value) }

    /** For testing: work out and log what Zen sync would upload, without uploading. */
    var zenSyncDryRun: Boolean
        get() = prefs.getBoolean(KEY_ZEN_SYNC_DRY_RUN, false)
        set(value) = prefs.edit { putBoolean(KEY_ZEN_SYNC_DRY_RUN, value) }

    /**
     * Pages' default zoom, in percent, on both screens. Gecko reads it when Ensō starts, so a change applies
     * after a restart.
     */
    var pageZoomPercent: Int
        get() = prefs.getInt(KEY_PAGE_ZOOM_PERCENT, 80)
        set(value) = prefs.edit { putInt(KEY_PAGE_ZOOM_PERCENT, value) }

    /** How long a Quick Tab lasts after it was last viewed. */
    var quickTabLifetimeHours: Int
        get() = prefs.getInt(KEY_QUICK_TAB_LIFETIME_HOURS, 24)
        set(value) = prefs.edit { putInt(KEY_QUICK_TAB_LIFETIME_HOURS, value) }

    /** Google's suggestions in bottom search, which sends what's typed to Google as you type. */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_SUGGESTIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_SEARCH_SUGGESTIONS, value) }

    /** The ensō fidget on the blank start page. */
    var showEnsoOnNewTabs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ENSO_ON_NEW_TABS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ENSO_ON_NEW_TABS, value) }

    /** How long the app must be away before it reopens on the start screen. 0 means it never does. */
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

    /** Whether Ensō has installed its bundled extensions (uBlock Origin) once; removed, they stay removed. */
    var bundledExtensionsInstalled: Boolean
        get() = prefs.getBoolean(KEY_BUNDLED_EXTENSIONS_INSTALLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BUNDLED_EXTENSIONS_INSTALLED, value) }

    /** How long after a download the actions sheet (and the ⋮ menu unfolded) keeps its Downloads button. */
    var recentDownloadsHours: Int
        get() = prefs.getInt(KEY_RECENT_DOWNLOADS_HOURS, 24)
        set(value) = prefs.edit { putInt(KEY_RECENT_DOWNLOADS_HOURS, value) }

    /** Whether the first download has asked Android for the notification permission (step 12); it asks only once. */
    var downloadNotificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ASKED, value) }

    /** When the app last went to the background. */
    var lastStoppedAt: Long?
        get() = prefs.getLong(KEY_LAST_STOPPED_AT, 0L).takeIf { it > 0 }
        set(value) = prefs.edit { putLong(KEY_LAST_STOPPED_AT, value ?: 0L) }

    private companion object {
        // The key predates the bottom bar, when it also covered the cover; it stays so the saved choice carries over.
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar_with_top_bar"
        const val KEY_COVER_STATUS_BAR = "cover_status_bar"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_TRACKING_PROTECTION = "tracking_protection"
        const val KEY_SHOW_ENSO_ON_NEW_TABS = "show_enso_on_new_tabs"
        const val KEY_SEARCH_SUGGESTIONS = "search_suggestions"
        const val KEY_QUICK_TABS_IN_TAB_SHEET = "quick_tabs_in_tab_sheet"
        const val KEY_SWITCH_SPACES_FROM_SHEET_HEADER = "switch_spaces_from_sheet_header"
        const val KEY_SPACE_MENU_HOLD_MS = "space_menu_hold_ms"
        const val KEY_SPACE_MENU_LEFT_HANDED = "space_menu_left_handed"
        const val KEY_UNFOLDED_PORTRAIT_LAYOUT = "unfolded_portrait_layout"
        const val KEY_INNER_PAGE_BORDER = "inner_page_border"
        const val KEY_DESKTOP_SITES_UNFOLDED = "desktop_sites_unfolded"
        const val KEY_DESKTOP_SITES_IN_SPLIT = "desktop_sites_in_split"
        const val KEY_INNER_SIDEBAR_WIDTH = "inner_sidebar_width_dp"

        /** The width the user settled on by dragging. */
        const val DEFAULT_SIDEBAR_WIDTH_DP = 240f
        const val KEY_SPACE_MENU_STRIP_HEIGHT = "space_menu_strip_height"
        const val KEY_ESSENTIALS_PER_CONTAINER = "essentials_per_container"
        const val KEY_SYNC_OPEN_TABS = "sync_open_tabs"
        const val KEY_SYNC_TAB_NAVIGATIONS = "sync_tab_navigations"
        const val KEY_ZEN_SYNC_DRY_RUN = "zen_sync_dry_run"
        const val KEY_UPDATE_EXTENSIONS = "update_extensions"
        const val KEY_QUICK_TAB_LIFETIME_HOURS = "quick_tab_lifetime_hours"
        const val KEY_PAGE_ZOOM_PERCENT = "page_zoom_percent"
        const val KEY_START_SCREEN_AFTER_MINUTES = "start_screen_after_minutes"
        const val KEY_LAST_STOPPED_AT = "last_stopped_at"
        const val KEY_INNER_SIDEBAR_EXPANDED = "inner_sidebar_expanded"
        const val KEY_BUNDLED_EXTENSIONS_INSTALLED = "bundled_extensions_installed"
        const val KEY_DOWNLOAD_NOTIFICATIONS_ASKED = "download_notifications_asked"
        const val KEY_RECENT_DOWNLOADS_HOURS = "recent_downloads_hours"
    }
}
