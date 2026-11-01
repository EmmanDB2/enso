package app.enso.browser.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.enso.browser.CoverStatusBar
import app.enso.browser.R
import app.enso.browser.Settings
import app.enso.browser.ThemeMode
import app.enso.browser.TrackingProtectionLevel
import app.enso.browser.UnfoldedPortraitLayout
import app.enso.browser.sync.ZenSyncStatus

/** A preference All settings changed, so the app can apply it at once where it can. */
enum class Setting {
    SyncOpenTabs, SyncNavigations, EssentialsPerContainer,
    Theme, PageZoom, CoverStatusBar, HideStatusBarUnfolded, PageBorder,
    UnfoldedPortrait, DesktopSitesUnfolded, DesktopSitesInSplit,
    QuickTabLifetime, QuickTabsInSheet, StartScreenAfter, SheetHeaderSwipe,
    HoldTime, LeftHanded, EdgeStrip,
    TrackingProtection,
    UpdateExtensions,
}

/** What All settings asks the app to do. */
interface AllSettingsActions {
    fun openZenSync()

    /** [setting] has been written to [Settings]. */
    fun changed(setting: Setting)
}

/**
 * Every preference in [Settings] (step 10), grouped as the user approved: sync, appearance, layout, tabs, the space
 * menu, privacy and extensions. It writes them straight to [settings], then tells the app ([AllSettingsActions.changed]).
 * The sidebar's width (set by dragging) and Zen sync's dry run (for testing) aren't here.
 */
@Composable
fun AllSettingsContent(settings: Settings, zenStatus: ZenSyncStatus, actions: AllSettingsActions, modifier: Modifier = Modifier) {
    // Settings aren't snapshot state, so a change bumps this to read them again.
    var revision by remember { mutableIntStateOf(0) }
    val values = remember(revision) { SettingsValues.of(settings) }
    fun change(setting: Setting, write: Settings.() -> Unit) {
        settings.write()
        revision++
        actions.changed(setting)
    }

    Column(modifier.fillMaxWidth()) {
        SettingsGroup(stringResource(R.string.settings_group_sync))
        LinkRow(
            stringResource(R.string.menu_zen_sync),
            when (zenStatus) {
                ZenSyncStatus.Starting -> null
                ZenSyncStatus.SignedOut -> stringResource(R.string.settings_zen_signed_out)
                is ZenSyncStatus.SignedIn -> zenStatus.email ?: stringResource(R.string.settings_zen_signed_in)
            },
            R.drawable.ic_sync,
            actions::openZenSync,
        )
        SwitchRow(
            stringResource(R.string.settings_sync_open_tabs),
            values.syncOpenTabs,
            { on -> change(Setting.SyncOpenTabs) { syncOpenTabs = on } },
            stringResource(R.string.settings_sync_open_tabs_detail),
        )
        SwitchRow(
            stringResource(R.string.settings_sync_navigations),
            values.syncTabNavigations,
            { on -> change(Setting.SyncNavigations) { syncTabNavigations = on } },
            stringResource(R.string.settings_sync_navigations_detail),
        )
        SwitchRow(
            stringResource(R.string.settings_essentials_per_container),
            values.essentialsPerContainer,
            { on -> change(Setting.EssentialsPerContainer) { essentialsPerContainer = on } },
            stringResource(R.string.settings_essentials_per_container_detail),
        )

        SettingsGroup(stringResource(R.string.settings_group_appearance))
        ChoiceRow(
            stringResource(R.string.settings_theme),
            listOf(
                ThemeMode.System to stringResource(R.string.settings_theme_system),
                ThemeMode.Light to stringResource(R.string.settings_theme_light),
                ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
            ),
            values.themeMode,
            { mode -> change(Setting.Theme) { themeMode = mode } },
        )
        StepperRow(
            stringResource(R.string.settings_page_zoom),
            stringResource(R.string.settings_page_zoom_detail),
            values.pageZoomPercent,
            range = 50..150,
            step = 10,
            label = { "$it%" },
        ) { percent -> change(Setting.PageZoom) { pageZoomPercent = percent } }
        ChoiceRow(
            stringResource(R.string.settings_cover_status_bar),
            listOf(
                CoverStatusBar.Hidden to stringResource(R.string.settings_cover_status_bar_hidden),
                CoverStatusBar.OverPage to stringResource(R.string.settings_cover_status_bar_shown),
                CoverStatusBar.TintedStrip to stringResource(R.string.settings_cover_status_bar_strip),
            ),
            values.coverStatusBar,
            { choice -> change(Setting.CoverStatusBar) { coverStatusBar = choice } },
        )
        SwitchRow(
            stringResource(R.string.settings_hide_status_bar_unfolded),
            values.hideStatusBarUnfolded,
            { on -> change(Setting.HideStatusBarUnfolded) { hideStatusBarUnfolded = on } },
        )
        SwitchRow(
            stringResource(R.string.settings_page_border),
            values.innerPageBorder,
            { on -> change(Setting.PageBorder) { innerPageBorder = on } },
        )

        SettingsGroup(stringResource(R.string.settings_group_layout))
        ChoiceRow(
            stringResource(R.string.settings_unfolded_portrait),
            listOf(
                UnfoldedPortraitLayout.Mobile to stringResource(R.string.settings_unfolded_portrait_cover),
                UnfoldedPortraitLayout.Desktop to stringResource(R.string.settings_unfolded_portrait_sidebar),
            ),
            values.unfoldedPortraitLayout,
            { layout -> change(Setting.UnfoldedPortrait) { unfoldedPortraitLayout = layout } },
        )
        SwitchRow(
            stringResource(R.string.settings_desktop_unfolded),
            values.desktopSitesUnfolded,
            { on -> change(Setting.DesktopSitesUnfolded) { desktopSitesUnfolded = on } },
        )
        SwitchRow(
            stringResource(R.string.settings_desktop_in_split),
            values.desktopSitesInSplit,
            { on -> change(Setting.DesktopSitesInSplit) { desktopSitesInSplit = on } },
        )

        SettingsGroup(stringResource(R.string.settings_group_tabs))
        ChoiceRow(
            stringResource(R.string.settings_quick_tab_lifetime),
            listOf(
                12 to stringResource(R.string.settings_hours_12),
                24 to stringResource(R.string.settings_day_1),
                72 to stringResource(R.string.settings_days_3),
                168 to stringResource(R.string.settings_week_1),
            ),
            values.quickTabLifetimeHours,
            { hours -> change(Setting.QuickTabLifetime) { quickTabLifetimeHours = hours } },
        )
        SwitchRow(
            stringResource(R.string.settings_quick_tabs_in_sheet),
            values.quickTabsInTabSheet,
            { on -> change(Setting.QuickTabsInSheet) { quickTabsInTabSheet = on } },
            stringResource(R.string.settings_quick_tabs_in_sheet_detail),
        )
        ChoiceRow(
            stringResource(R.string.settings_start_screen_after),
            listOf(
                15 to stringResource(R.string.settings_minutes_15),
                30 to stringResource(R.string.settings_minutes_30),
                60 to stringResource(R.string.settings_hour_1),
                0 to stringResource(R.string.settings_never),
            ),
            values.startScreenAfterMinutes,
            { minutes -> change(Setting.StartScreenAfter) { startScreenAfterMinutes = minutes } },
        )
        SwitchRow(
            stringResource(R.string.settings_sheet_header_swipe),
            values.switchSpacesFromSheetHeader,
            { on -> change(Setting.SheetHeaderSwipe) { switchSpacesFromSheetHeader = on } },
        )

        SettingsGroup(stringResource(R.string.settings_group_space_menu))
        val holdLabel = stringResource(R.string.settings_hold_ms)
        SliderRow(
            stringResource(R.string.settings_hold_to_open),
            values.spaceMenuHoldMs,
            range = 200..600,
            step = 50,
            label = { holdLabel.format(it) },
        ) { ms -> change(Setting.HoldTime) { spaceMenuHoldMs = ms.toLong() } }
        SwitchRow(
            stringResource(R.string.settings_left_handed),
            values.spaceMenuLeftHanded,
            { on -> change(Setting.LeftHanded) { spaceMenuLeftHanded = on } },
            stringResource(R.string.settings_left_handed_detail),
        )
        ChoiceRow(
            stringResource(R.string.settings_edge_strip),
            listOf(
                1f to stringResource(R.string.settings_edge_whole),
                0.5f to stringResource(R.string.settings_edge_half),
                0.33f to stringResource(R.string.settings_edge_third),
            ),
            values.spaceMenuStripHeight,
            { height -> change(Setting.EdgeStrip) { spaceMenuStripHeight = height } },
        )

        SettingsGroup(stringResource(R.string.settings_group_privacy))
        ChoiceRow(
            stringResource(R.string.site_settings_tracking),
            listOf(
                TrackingProtectionLevel.Standard to stringResource(R.string.settings_tracking_standard),
                TrackingProtectionLevel.Strict to stringResource(R.string.settings_tracking_strict),
            ),
            values.trackingProtection,
            { level -> change(Setting.TrackingProtection) { trackingProtection = level } },
        )

        SettingsGroup(stringResource(R.string.extensions_title))
        SwitchRow(
            stringResource(R.string.settings_update_extensions),
            values.updateExtensions,
            { on -> change(Setting.UpdateExtensions) { updateExtensions = on } },
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** The preferences as they are, read once per change. */
private data class SettingsValues(
    val syncOpenTabs: Boolean,
    val syncTabNavigations: Boolean,
    val essentialsPerContainer: Boolean,
    val themeMode: ThemeMode,
    val pageZoomPercent: Int,
    val coverStatusBar: CoverStatusBar,
    val hideStatusBarUnfolded: Boolean,
    val innerPageBorder: Boolean,
    val unfoldedPortraitLayout: UnfoldedPortraitLayout,
    val desktopSitesUnfolded: Boolean,
    val desktopSitesInSplit: Boolean,
    val quickTabLifetimeHours: Int,
    val quickTabsInTabSheet: Boolean,
    val startScreenAfterMinutes: Int,
    val switchSpacesFromSheetHeader: Boolean,
    val spaceMenuHoldMs: Int,
    val spaceMenuLeftHanded: Boolean,
    val spaceMenuStripHeight: Float,
    val trackingProtection: TrackingProtectionLevel,
    val updateExtensions: Boolean,
) {
    companion object {
        fun of(settings: Settings) = with(settings) {
            SettingsValues(
                syncOpenTabs, syncTabNavigations, essentialsPerContainer,
                themeMode, pageZoomPercent, coverStatusBar, hideStatusBarUnfolded, innerPageBorder,
                unfoldedPortraitLayout, desktopSitesUnfolded, desktopSitesInSplit,
                quickTabLifetimeHours, quickTabsInTabSheet, startScreenAfterMinutes, switchSpacesFromSheetHeader,
                spaceMenuHoldMs.toInt(), spaceMenuLeftHanded, spaceMenuStripHeight,
                trackingProtection,
                updateExtensions,
            )
        }
    }
}
