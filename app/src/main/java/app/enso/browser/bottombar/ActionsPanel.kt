package app.enso.browser.bottombar

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.Space
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.PageState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.SpaceIcon
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import app.enso.browser.settings.SettingRow
import app.enso.browser.settings.SwitchRow

/** What the Keep tile can do for the tab on screen. */
internal sealed interface KeepState {
    /** No page on screen. */
    data object None : KeepState

    /** A Quick Tab: Keep keeps it in the space, and holding Keep picks another space. */
    data class Quick(val tabId: String) : KeepState

    /** A normal open tab of the space: "Kept", and tapping makes it a Quick Tab again. */
    data class Kept(val tabId: String) : KeepState

    /** A pinned tab or an Essential: "Kept", but they're changed from the tab sheet. */
    data object Fixed : KeepState
}

/**
 * The actions sheet (board B3): back, forward, the domain, copy link and reload, which is the only place the URL
 * shows on the cover; then Reader, Find, Keep and Share as one joined group; then plain rows, as in the settings:
 * Desktop site, Downloads (only after a recent download), Extensions and Site Settings (All settings when there's no
 * site on screen). The user had it redone after Arc Search's actions sheet, which was less crowded.
 */
@Composable
internal fun ActionsPanel(page: PageState, space: Space, keep: KeepState, showDownloads: Boolean, reader: BarReader, actions: BottomBarActions) {
    val hasPage = page.url.isNotEmpty()
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            NavigationPill(page, actions)
            Spacer(Modifier.height(12.dp))
            // One group with hairline gaps, its outer corners rounded, as Arc Search's tiles are.
            Row(Modifier.clip(RoundedCornerShape(18.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // Reader: on an article it shows the page as one; while reading it opens Reader's panel.
                ActionTile(
                    R.drawable.ic_reader,
                    R.string.action_reader,
                    enabled = reader.available || reader.active,
                    Modifier.weight(1f),
                    highlighted = reader.active,
                ) {
                    if (reader.active) actions.showPanel(BarPanel.Reader) else actions.showReader()
                }
                ActionTile(R.drawable.ic_search, R.string.action_find, enabled = hasPage, Modifier.weight(1f)) { actions.showPanel(BarPanel.Find) }
                KeepTile(space, keep, actions, Modifier.weight(1f))
                ActionTile(R.drawable.ic_share, R.string.action_share, enabled = hasPage, Modifier.weight(1f), onClick = actions::share)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (hasPage) {
            SwitchRow(stringResource(R.string.site_settings_desktop), page.desktop, actions::setDesktopSite, icon = R.drawable.ic_desktop)
        }
        if (showDownloads) {
            SettingRow(stringResource(R.string.downloads_title), icon = R.drawable.ic_download, onClick = { actions.showPanel(BarPanel.Downloads) })
        }
        SettingRow(stringResource(R.string.action_extensions), icon = R.drawable.ic_extensions, onClick = { actions.showPanel(BarPanel.Extensions) })
        SettingRow(stringResource(R.string.action_site_settings), icon = R.drawable.ic_settings, onClick = actions::openSettings)
        Spacer(Modifier.height(8.dp))
    }
}

/** Back and forward, the domain (tap it to edit the URL) centered, then copy link and reload or stop. */
@Composable
private fun NavigationPill(page: PageState, actions: BottomBarActions) {
    val host = AddressInput.displayHost(page.url)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(PillFill).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavButton(R.drawable.ic_arrow_left, R.string.menu_back, page.canGoBack, actions::back)
        NavButton(R.drawable.ic_arrow_right, R.string.menu_forward, page.canGoForward, actions::forward)
        Text(
            host.ifEmpty { stringResource(R.string.address_placeholder) },
            style = if (host.isEmpty()) EnsoType.Row.copy(color = EnsoColors.TextSecondary) else EnsoType.Row.copy(fontSize = 17.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable { if (host.isEmpty()) actions.newSearch() else actions.editUrl() }
                .padding(vertical = 12.dp),
        )
        NavButton(R.drawable.ic_link, R.string.action_copy_url, page.url.isNotEmpty(), actions::copyUrl)
        if (page.loading) {
            NavButton(R.drawable.ic_close, R.string.action_stop, enabled = true, actions::stop)
        } else {
            NavButton(R.drawable.ic_reload, R.string.action_reload, enabled = page.url.isNotEmpty(), actions::reload)
        }
    }
}

@Composable
private fun NavButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(label),
            Modifier.size(24.dp),
            tint = EnsoColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.3f),
        )
    }
}

/** Keep, with the space's icon: keeps a Quick Tab in the space (hold for another), or shows "Kept". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeepTile(space: Space, keep: KeepState, actions: BottomBarActions, modifier: Modifier) {
    val enabled = keep is KeepState.Quick || keep is KeepState.Kept
    val kept = keep is KeepState.Kept || keep is KeepState.Fixed
    Column(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(TileCorner))
            .background(PillFill)
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    when (keep) {
                        is KeepState.Quick -> actions.keep(keep.tabId)
                        is KeepState.Kept -> actions.unkeep(keep.tabId)
                        else -> {}
                    }
                },
                onLongClick = (keep as? KeepState.Quick)?.let { quick -> { actions.keepElsewhere(quick.tabId) } },
            )
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SpaceIcon(space.icon, size = 24.dp, tint = Color(space.accent), fallbackName = space.name)
        Spacer(Modifier.height(7.dp))
        Text(stringResource(if (kept) R.string.action_kept else R.string.action_keep), style = TileLabel, maxLines = 1)
    }
}

@Composable
private fun ActionTile(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    modifier: Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(TileCorner))
            .background(PillFill)
            .then(if (highlighted) Modifier.border(1.5.dp, EnsoColors.TextPrimary.copy(alpha = 0.6f), RoundedCornerShape(TileCorner)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = EnsoColors.TextPrimary)
        Spacer(Modifier.height(7.dp))
        Text(stringResource(label), style = TileLabel, maxLines = 1)
    }
}

/** A tile's own corners, inside the group's rounded ones. */
private val TileCorner = 4.dp

private val TileLabel get() = EnsoType.Row.copy(fontSize = 13.5.sp)

