package app.enso.browser.tabsheet

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.TabPlace
import app.enso.browser.spaces.addToEssentials
import app.enso.browser.spaces.deleteFolder
import app.enso.browser.spaces.find
import app.enso.browser.spaces.keepQuickTab
import app.enso.browser.spaces.moveToFolder
import app.enso.browser.spaces.moveToNewFolder
import app.enso.browser.spaces.pin
import app.enso.browser.spaces.removeFromFolder
import app.enso.browser.spaces.renameFolder
import app.enso.browser.spaces.splitOf
import app.enso.browser.spaces.unpin
import app.enso.browser.spaces.unsplit
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.SpaceIcon
import app.enso.browser.ui.displayTitle
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** What was held, and where its row is on screen. */
sealed interface MenuTarget {
    val bounds: Rect

    data class ForTab(val tabId: String, override val bounds: Rect) : MenuTarget

    data class ForFolder(val folderId: String, override val bounds: Rect) : MenuTarget
}

private class MenuItem(
    @DrawableRes val icon: Int,
    @StringRes val label: Int = 0,
    /** Shown instead of [label], for names that come from data (folders). */
    val text: String? = null,
    /** A space's icon, in its accent, shown instead of [icon]. */
    val spaceIcon: String? = null,
    /** The primary action, on a light gray fill (board 4b). */
    val highlighted: Boolean = false,
    val destructive: Boolean = false,
    val opensMore: Boolean = false,
    val onClick: () -> Unit,
)

private sealed interface FolderDialog {
    data class New(val tabId: String) : FolderDialog

    data class Rename(val folder: Folder) : FolderDialog
}

/**
 * Hold menu: the held row lifts, everything else dims, and a white menu opens beside it (board 4b).
 * Quick Tabs get the spec's menu. Other tabs and folders get organizing actions (pin, folders,
 * Essentials), which sync to Zen when signed in.
 */
@Composable
internal fun TabMenu(
    target: MenuTarget,
    spaces: SpacesState,
    selectedTabId: String?,
    quickTabLifetimeMs: Long,
    actions: TabSheetActions,
    onDismiss: () -> Unit,
) {
    BlockSpaceMenu()
    var choosingFolder by remember(target) { mutableStateOf(false) }
    var dialog by remember(target) { mutableStateOf<FolderDialog?>(null) }
    val accent = Color(spaces.currentSpace.accent)

    fun edit(transform: (SpacesState) -> SpacesState) {
        actions.edit(transform)
        onDismiss()
    }

    val header: String
    val items: List<MenuItem?> // null is a divider
    when (target) {
        is MenuTarget.ForTab -> {
            val entry = spaces.find(target.tabId) ?: return DismissGone(onDismiss)
            val id = target.tabId
            val quick = entry.tab.quick
            header = if (quick != null) {
                // The tab on screen restarts its clock when you leave it, so it has the full time.
                val viewedAt = if (id == selectedTabId) System.currentTimeMillis() else quick.lastViewedAt
                val remaining = quickTabLifetimeMs - (System.currentTimeMillis() - viewedAt)
                stringResource(R.string.quick_tab_header, clearsIn(remaining))
            } else {
                AddressInput.displayHost(entry.tab.url).ifEmpty { entry.tab.displayTitle() }
            }
            val space = spaces.currentSpace
            val unsplit = spaces.splitOf(id)?.let { split ->
                MenuItem(R.drawable.ic_split, R.string.menu_unsplit_tabs) { edit { it.unsplit(split.id) } }
            }
            items = if (quick != null) {
                buildList {
                    add(
                        MenuItem(
                            icon = 0,
                            text = stringResource(R.string.menu_add_to_space, space.name),
                            spaceIcon = space.icon,
                            highlighted = true,
                        ) { edit { it.keepQuickTab(id) } },
                    )
                    if (spaces.spaces.size > 1) {
                        add(MenuItem(R.drawable.ic_add_to_space, R.string.menu_add_to_another_space, opensMore = true) {
                            onDismiss()
                            actions.addToAnotherSpace(id)
                        })
                    }
                    unsplit?.let(::add)
                    add(null)
                    add(
                        MenuItem(R.drawable.ic_close, R.string.action_close_tab, destructive = true) {
                            actions.close(id)
                            onDismiss()
                        },
                    )
                }
            } else if (choosingFolder) {
                val currentFolder = (entry.place as? TabPlace.Pinned)?.folder?.id
                spaces.currentSpace.folders.filterNot { it.id == currentFolder }.map { folder ->
                    MenuItem(R.drawable.ic_folder, text = folder.name) { edit { it.moveToFolder(id, folder.id) } }
                } + MenuItem(R.drawable.ic_edit, R.string.menu_new_folder) { dialog = FolderDialog.New(id) }
            } else {
                val moveToFolder = MenuItem(R.drawable.ic_folder, R.string.menu_move_to_folder, opensMore = true) {
                    choosingFolder = true
                }
                val unload = MenuItem(R.drawable.ic_space_moon, R.string.action_unload_tab) {
                    actions.unload(id)
                    onDismiss()
                }
                val close = MenuItem(R.drawable.ic_close, R.string.action_close_tab, destructive = true) {
                    actions.close(id)
                    onDismiss()
                }
                when (val place = entry.place) {
                    TabPlace.Essential -> listOf(
                        MenuItem(R.drawable.ic_essentials, R.string.menu_remove_from_essentials) { edit { it.unpin(id) } },
                    )
                    is TabPlace.Pinned -> listOfNotNull(
                        unsplit,
                        place.folder?.let {
                            MenuItem(R.drawable.ic_folder, R.string.menu_remove_from_folder) { edit { it.removeFromFolder(id) } }
                        },
                        MenuItem(R.drawable.ic_pin, R.string.menu_unpin_tab) { edit { it.unpin(id) } },
                        MenuItem(R.drawable.ic_essentials, R.string.menu_add_to_essentials) { edit { it.addToEssentials(id) } },
                        moveToFolder,
                    ) + listOf(null, unload)
                    is TabPlace.Open -> listOfNotNull(unsplit) + listOf(
                        MenuItem(R.drawable.ic_pin, R.string.menu_pin_tab) { edit { it.pin(id) } },
                        MenuItem(R.drawable.ic_essentials, R.string.menu_add_to_essentials) { edit { it.addToEssentials(id) } },
                        moveToFolder,
                        null,
                        close,
                    )
                }
            }
        }
        is MenuTarget.ForFolder -> {
            val folder = spaces.currentSpace.folders.firstOrNull { it.id == target.folderId } ?: return DismissGone(onDismiss)
            val count = folder.tabs.size
            header = stringResource(R.string.menu_header_folder, pluralStringResource(R.plurals.tab_count, count, count))
            items = listOf(
                MenuItem(R.drawable.ic_edit, R.string.menu_rename_folder) { dialog = FolderDialog.Rename(folder) },
                null,
                MenuItem(R.drawable.ic_close, R.string.menu_delete_folder, destructive = true) {
                    edit { it.deleteFolder(folder.id) }
                },
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        LiftedRow(target, spaces)
        MenuCardPlacement(target.bounds) {
            MenuCard(header, items, accent)
        }
    }

    when (val d = dialog) {
        is FolderDialog.New -> FolderNameDialog(
            title = R.string.dialog_new_folder_title,
            confirm = R.string.dialog_create,
            initial = "",
            accent = accent,
            onConfirm = { name -> edit { it.moveToNewFolder(d.tabId, name) } },
            onDismiss = { dialog = null },
        )
        is FolderDialog.Rename -> FolderNameDialog(
            title = R.string.dialog_rename_folder_title,
            confirm = R.string.dialog_rename,
            initial = d.folder.name,
            accent = accent,
            onConfirm = { name -> edit { it.renameFolder(d.folder.id, name) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** "9 hours", "45 minutes" or "under a minute". */
@Composable
internal fun clearsIn(remainingMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs.coerceAtLeast(0)).toInt()
    return when {
        minutes >= 60 -> ((minutes + 30) / 60).let { pluralStringResource(R.plurals.duration_hours, it, it) }
        minutes >= 1 -> pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
        else -> stringResource(R.string.duration_under_a_minute)
    }
}

/** The held tab or folder disappeared (closed by its page, say), so close the menu. */
@Composable
private fun DismissGone(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

/** A white copy of the held row, drawn above the dim layer where the row is. */
@Composable
private fun LiftedRow(target: MenuTarget, spaces: SpacesState) {
    val bounds = target.bounds
    val density = LocalDensity.current
    val shape = RoundedCornerShape(14.dp)
    val heldTab = (target as? MenuTarget.ForTab)?.let { spaces.find(it.tabId)?.tab }
    Box(
        Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .shadow(10.dp, shape)
            .background(EnsoColors.CurrentTab, shape)
            .then(if (heldTab != null) Modifier.quickTabOutline(heldTab, shape) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (target) {
            is MenuTarget.ForTab -> {
                val entry = spaces.find(target.tabId) ?: return@Box
                if (entry.place is TabPlace.Essential) {
                    Favicon(entry.tab, Modifier.align(Alignment.Center))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Favicon(entry.tab)
                        Spacer(Modifier.width(14.dp))
                        Text(entry.tab.displayTitle(), style = EnsoType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            is MenuTarget.ForFolder -> {
                val folder = spaces.currentSpace.folders.firstOrNull { it.id == target.folderId } ?: return@Box
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color(spaces.currentSpace.accent),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(folder.name, style = EnsoType.RowTitle, maxLines = 1)
                }
            }
        }
    }
}

/** Puts the menu just below the held row, or above it when there isn't room. */
@Composable
private fun MenuCardPlacement(bounds: Rect, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 8.dp.roundToPx()
        val card = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val below = bounds.bottom.roundToInt() + margin
        val y = if (below + card.height <= constraints.maxHeight - margin) {
            below
        } else {
            (bounds.top.roundToInt() - margin - card.height).coerceAtLeast(margin)
        }
        val x = bounds.left.roundToInt().coerceIn(margin, (constraints.maxWidth - card.width - margin).coerceAtLeast(margin))
        layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
    }
}

@Composable
private fun MenuCard(header: String, items: List<MenuItem?>, accent: Color) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(280.dp)
            .shadow(12.dp, shape)
            .background(Color.White, shape)
            .clip(shape)
            // Taps inside the card shouldn't reach the dim layer and dismiss it.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Text(
            header,
            style = EnsoType.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
        )
        items.forEach { item ->
            if (item == null) {
                Box(
                    Modifier
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(EnsoColors.TextSecondary.copy(alpha = 0.2f)),
                )
            } else {
                MenuRow(item, accent)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun MenuRow(item: MenuItem, accent: Color) {
    val color = if (item.destructive) EnsoColors.CloseMenuText else EnsoColors.TextPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (item.highlighted) Modifier.background(EnsoColors.TextPrimary.copy(alpha = 0.05f)) else Modifier)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.spaceIcon != null) {
            SpaceIcon(item.spaceIcon, size = 22.dp, tint = accent)
        } else {
            Icon(painterResource(item.icon), contentDescription = null, Modifier.size(22.dp), tint = color)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            item.text ?: stringResource(item.label),
            style = EnsoType.MenuItem.copy(fontSize = 17.sp, color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.opensMore) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextSecondary)
        }
    }
}

@Composable
private fun FolderNameDialog(
    @StringRes title: Int,
    @StringRes confirm: Int,
    initial: String,
    accent: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    val valid = name.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim()) }, enabled = valid) {
                Text(stringResource(confirm), style = EnsoType.MenuItem.copy(color = if (valid) accent else EnsoColors.TextSecondary))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), style = EnsoType.MenuItem.copy(color = EnsoColors.TextSecondary))
            }
        },
        title = { Text(stringResource(title), style = EnsoType.SpaceName.copy(fontSize = 20.sp)) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = EnsoType.Row,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(EnsoColors.FieldFill)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.text.isEmpty()) {
                            Text(stringResource(R.string.dialog_folder_name_label), style = EnsoType.Row.copy(color = EnsoColors.TextSecondary))
                        }
                        inner()
                    }
                },
            )
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.White,
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
