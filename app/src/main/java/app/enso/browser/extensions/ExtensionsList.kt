package app.enso.browser.extensions

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.support.webextensions.WebExtensionSupport
import kotlin.math.roundToInt

/** An installed extension as the Extensions list shows it. */
data class ExtensionRow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** It has a toolbar button, so tapping it opens its popup. */
    val hasPopup: Boolean,
    /** What its toolbar button shows for the tab on screen, such as uBlock Origin's count of blocked requests. */
    val badge: String?,
    val loadIcon: (suspend (Int) -> Bitmap?)?,
)

/** The user's extensions in [state], built-in ones (Gecko's own system add-ons) left out. */
fun extensionRows(state: BrowserState): List<ExtensionRow> = state.extensions.values
    .filterNot { it.isBuiltIn }
    .sortedBy { it.name.orEmpty().lowercase() }
    .map { extension ->
        // The tab on screen can override the button, as uBlock Origin does with its count.
        val override = state.selectedTab?.extensionState?.get(extension.id)?.browserAction
        val action = extension.browserAction?.let { global -> override?.let(global::copyWithOverride) ?: global }
        ExtensionRow(
            id = extension.id,
            name = extension.name.orEmpty().ifBlank { extension.id },
            enabled = extension.enabled,
            hasPopup = action != null,
            badge = action?.badgeText?.ifBlank { null },
            // Without a toolbar button (or switched off, when it has none), the extension's own icon.
            loadIcon = action?.loadIcon ?: WebExtensionSupport.installedExtensions[extension.id]?.let { it::loadIcon },
        )
    }

/** What the Extensions list asks the app to do. */
interface ExtensionListActions {
    /** Opens its popup, or its settings when it has no toolbar button. */
    fun open(id: String)

    fun setEnabled(id: String, enabled: Boolean)

    fun settings(id: String)

    fun remove(id: String)

    /** Opens Mozilla's add-on site, where the user finds and installs more. */
    fun findMore()
}

/**
 * The user's extensions: icon, name, badge and an on/off switch. Tapping one opens its popup; holding it offers
 * Settings and Remove. A link to Mozilla's add-on site ends the list. Used by the bottom bar's Extensions panel and
 * the inner screen's Extensions card.
 */
@Composable
fun ExtensionsList(rows: List<ExtensionRow>, actions: ExtensionListActions, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.extensions_none),
                style = EnsoType.Secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        rows.forEach { row -> ExtensionRowItem(row, actions) }
        FindMoreLink(actions::findMore)
    }
}

/** "Find more add-ons here", lined up with the extensions' names. */
@Composable
private fun FindMoreLink(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_open_tab), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(R.string.extensions_find_more),
            style = EnsoType.Row.copy(fontSize = 16.sp, textDecoration = TextDecoration.Underline),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtensionRowItem(row: ExtensionRow, actions: ExtensionListActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { if (row.enabled) actions.open(row.id) }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIcon(row, Modifier.alpha(if (row.enabled) 1f else 0.4f))
            Spacer(Modifier.width(14.dp))
            Text(
                row.name,
                style = EnsoType.RowTitle.copy(fontSize = 17.sp, color = if (row.enabled) EnsoColors.TextPrimary else EnsoColors.TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.enabled && row.badge != null) {
                Text(
                    row.badge,
                    style = EnsoType.Row.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EnsoColors.TextSecondary),
                    modifier = Modifier
                        .background(EnsoColors.FieldFill, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = { actions.setEnabled(row.id, it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = EnsoColors.TextPrimary,
                    checkedThumbColor = EnsoColors.Card,
                    uncheckedTrackColor = EnsoColors.FieldFill,
                    uncheckedBorderColor = EnsoColors.TextSecondary.copy(alpha = 0.4f),
                ),
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = EnsoColors.Card,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_settings), style = EnsoType.MenuItem) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null, tint = EnsoColors.TextPrimary) },
                onClick = {
                    menu = false
                    actions.settings(row.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.extension_remove), style = EnsoType.MenuItem.copy(color = EnsoColors.CloseMenuText)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = EnsoColors.CloseMenuText) },
                onClick = {
                    menu = false
                    actions.remove(row.id)
                },
            )
        }
    }
}

/**
 * The inner screen's Extensions card, from the sidebar's ⋮ menu: the list in a card over the dimmed page card
 * ([area], window pixels). A tap around it, or Back, closes it.
 */
@Composable
fun ExtensionsCard(
    area: Rect,
    areaCorner: Dp,
    rows: List<ExtensionRow>,
    actions: ExtensionListActions,
    onClose: () -> Unit,
    /** For the card itself, inside its dim layer: its entrance, say. */
    cardModifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    BlockSpaceMenu()
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Box(
            Modifier
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(areaCorner)),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                cardModifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.8f)
                    .heightIn(max = with(density) { area.height.toDp() } - 80.dp)
                    .shadow(24.dp, shape)
                    .background(EnsoColors.CurrentTab, shape)
                    .clip(shape)
                    // Taps on the card stay on it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.extensions_title),
                    style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
                ExtensionsList(rows, actions, Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }
}

/** The extension's toolbar icon, or a letter until it loads (or when it has none). */
@Composable
private fun ExtensionIcon(row: ExtensionRow, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { 32.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, row.id, row.loadIcon) {
        value = runCatching { row.loadIcon?.invoke(sizePx) }.getOrNull()?.asImageBitmap()
    }
    val image = icon
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier.size(32.dp))
    } else {
        LetterFavicon(row.name, "", modifier, size = 32.dp)
    }
}
