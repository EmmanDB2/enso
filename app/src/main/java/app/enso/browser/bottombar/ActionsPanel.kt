package app.enso.browser.bottombar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.Tab
import app.enso.browser.tabsheet.clearsIn
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.PageState
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.SpaceIcon

/**
 * The actions sheet (board B3): back, forward, the URL and reload, which is the only place the URL shows on the
 * cover; "Keep in [space]" when the tab on screen is a Quick Tab; then Reader, Find, Copy URL and Share, and
 * Extensions and Settings. Reader and Find are grayed out until they're built.
 */
@Composable
internal fun ActionsPanel(page: PageState, space: Space, quickTab: Tab?, quickTabLifetimeMs: Long, actions: BottomBarActions) {
    val hasPage = page.url.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        NavigationPill(page, actions)
        quickTab?.let { tab ->
            Spacer(Modifier.height(12.dp))
            KeepRow(space, remainingMs(tab, tab.id, quickTabLifetimeMs), { actions.keep(tab.id) }, { actions.keepElsewhere(tab.id) })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(R.drawable.ic_reader, R.string.action_reader, enabled = false, Modifier.weight(1f)) {}
            ActionTile(R.drawable.ic_search, R.string.action_find, enabled = false, Modifier.weight(1f)) {}
            ActionTile(R.drawable.ic_link, R.string.action_copy_url, enabled = hasPage, Modifier.weight(1f), onClick = actions::copyUrl)
            ActionTile(R.drawable.ic_share, R.string.action_share, enabled = hasPage, Modifier.weight(1f), onClick = actions::share)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton(R.drawable.ic_extensions, R.string.action_extensions, enabled = true, Modifier.weight(1f)) {
                actions.showPanel(BarPanel.Extensions)
            }
            WideButton(R.drawable.ic_settings, R.string.action_settings, enabled = true, Modifier.weight(1f)) {
                actions.showPanel(BarPanel.Settings)
            }
        }
        Spacer(Modifier.height(16.dp))
        BarDivider()
    }
}

/** For now just Sync with Zen; the settings screen comes later. Back returns to the actions sheet. */
@Composable
internal fun SettingsPanel(actions: BottomBarActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.showPanel(BarPanel.Actions) }) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.menu_back), tint = EnsoColors.TextPrimary)
            }
            Text(stringResource(R.string.action_settings), style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.height(8.dp))
        WideButton(R.drawable.ic_sync, R.string.menu_zen_sync, enabled = true, Modifier.fillMaxWidth(), onClick = actions::openZenSync)
        Spacer(Modifier.height(16.dp))
        BarDivider()
    }
}

/** Back, forward, the domain (tap it to edit the URL) and reload or stop. */
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

/** "Keep in [space]" for the Quick Tab on screen, with how long it has left. Holding it picks another space. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeepRow(space: Space, remainingMs: Long, onKeep: () -> Unit, onKeepElsewhere: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(EnsoColors.CurrentTab, shape)
            .border(2.dp, EnsoColors.QuickTabOutline, shape)
            .combinedClickable(onClick = onKeep, onLongClick = onKeepElsewhere)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpaceIcon(space.icon, size = 26.dp, tint = Color(space.accent), fallbackName = space.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bar_keep_in, space.name), style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            Text(stringResource(R.string.quick_tab_header, clearsIn(remainingMs)), style = EnsoType.Secondary)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextSecondary)
    }
}

@Composable
private fun ActionTile(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(84.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PillFill)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(26.dp), tint = EnsoColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(label), style = EnsoType.Row.copy(fontSize = 15.sp), maxLines = 1)
    }
}

@Composable
private fun WideButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(PillFill)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextPrimary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = EnsoType.Row.copy(fontSize = 17.sp))
    }
}
