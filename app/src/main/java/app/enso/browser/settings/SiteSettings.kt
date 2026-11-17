package app.enso.browser.settings

import app.enso.browser.permissions.labelOf
import app.enso.browser.permissions.iconOf
import app.enso.browser.permissions.SitePermission
import app.enso.browser.permissions.PermissionChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon

/** The page on screen, as its site settings show it (step 10). */
data class SiteState(
    val tabId: String,
    val url: String,
    val host: String,
    val title: String,
    val secure: Boolean,
    /** Who verified the site's certificate. */
    val issuer: String?,
    val desktop: Boolean,
    /** The user picked desktop or mobile for this site, rather than letting it follow the screen. */
    val desktopChosen: Boolean,
    val trackingProtection: Boolean,
    val blockedTrackers: Int,
)

/** What site settings ask the app to do. */
interface SiteSettingsActions {
    fun setDesktop(desktop: Boolean)

    fun setTrackingProtection(on: Boolean)

    /** Signs the user out of the site and forgets what it stored and its permission answers, then reloads it. */
    fun clearSiteData()

    /** The site's permission answers for its Permissions group (step 11), read again when [permissionsRevision] changes. */
    fun permissions(host: String): List<Pair<SitePermission, PermissionChoice>>

    val permissionsRevision: Int

    fun setPermission(permission: SitePermission, choice: PermissionChoice)

    fun openAllSettings()
}

/** The site's icon and host, heading its settings. */
@Composable
internal fun SiteTitle(site: SiteState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Favicon(site.title, site.url, site.tabId, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(site.host, style = SettingsType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The settings of the site on screen: its connection, desktop or mobile, tracking protection and clearing its data,
 * then a way on to All settings. On the cover it's a panel of the bar; unfolded, a card.
 */
@Composable
fun SiteSettingsContent(site: SiteState, actions: SiteSettingsActions, modifier: Modifier = Modifier) {
    var confirmClear by remember(site.host) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (site.secure) {
            SettingRow(
                stringResource(R.string.site_settings_secure),
                site.issuer?.let { stringResource(R.string.site_settings_verified_by, it) },
                R.drawable.ic_lock,
            )
        } else {
            SettingRow(stringResource(R.string.site_settings_not_secure), stringResource(R.string.site_settings_not_secure_detail), R.drawable.ic_lock_open)
        }
        SwitchRow(
            stringResource(R.string.site_settings_desktop),
            checked = site.desktop,
            onChange = actions::setDesktop,
            subtitle = stringResource(if (site.desktopChosen) R.string.site_settings_remembered else R.string.site_settings_follows_screen),
            icon = R.drawable.ic_desktop,
        )
        SwitchRow(
            stringResource(R.string.site_settings_tracking),
            checked = site.trackingProtection,
            onChange = actions::setTrackingProtection,
            subtitle = when {
                !site.trackingProtection -> stringResource(R.string.site_settings_tracking_off)
                site.blockedTrackers == 0 -> stringResource(R.string.site_settings_no_trackers)
                else -> pluralStringResource(R.plurals.site_settings_trackers_blocked, site.blockedTrackers, site.blockedTrackers)
            },
            icon = R.drawable.ic_shield,
        )
        val permissions = remember(site.host, actions.permissionsRevision) { actions.permissions(site.host) }
        SettingsGroup(stringResource(R.string.site_settings_permissions))
        permissions.forEach { (permission, choice) -> PermissionRow(permission, choice) { actions.setPermission(permission, it) } }
        Spacer(Modifier.height(12.dp))
        SettingRow(
            stringResource(R.string.site_settings_clear),
            icon = R.drawable.ic_trash,
            color = EnsoColors.CloseMenuText,
            onClick = { confirmClear = true },
        )
        SettingRow(stringResource(R.string.settings_all), icon = R.drawable.ic_settings, onClick = actions::openAllSettings)
    }
    if (confirmClear) {
        ConfirmClear(site.host, onDismiss = { confirmClear = false }) {
            confirmClear = false
            actions.clearSiteData()
        }
    }
}

/** A permission with the site's answer under it (Blocked in red); tapping it picks Ask, Allow or Block. */
@Composable
private fun PermissionRow(permission: SitePermission, choice: PermissionChoice, onChoose: (PermissionChoice) -> Unit) {
    MenuRow(
        stringResource(labelOf(permission)),
        value = stringResource(
            when (choice) {
                PermissionChoice.Allow -> R.string.permission_allowed
                PermissionChoice.Block -> R.string.permission_blocked
                PermissionChoice.Ask -> R.string.permission_ask
            },
        ),
        options = PermissionChoice.entries.map { option ->
            option to stringResource(
                when (option) {
                    PermissionChoice.Ask -> R.string.permission_ask
                    PermissionChoice.Allow -> R.string.permission_prompt_allow
                    PermissionChoice.Block -> R.string.permission_blocked
                },
            )
        },
        selected = choice,
        onSelect = onChoose,
        icon = iconOf(permission),
        valueColor = if (choice == PermissionChoice.Block) EnsoColors.CloseMenuText else EnsoColors.TextSecondary,
    )
}

/** Clearing a site's data signs the user out of it, so it asks first. A dialog, so it shows over a bar panel too. */
@Composable
private fun ConfirmClear(host: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape)
                .background(EnsoColors.Card, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.site_settings_clear_title), style = SettingsType.Heading)
            Text(stringResource(R.string.site_settings_clear_body, host), style = EnsoType.Secondary.copy(fontSize = 14.sp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(stringResource(R.string.extension_prompt_cancel), EnsoColors.FieldFill, EnsoColors.TextPrimary, onDismiss)
                DialogButton(stringResource(R.string.site_settings_clear_confirm), EnsoColors.CloseSwipe, Color.White, onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, fill: Color, text: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(fill, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = EnsoType.MenuItem.copy(fontSize = 15.sp, color = text))
    }
}
