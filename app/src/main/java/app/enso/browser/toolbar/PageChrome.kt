package app.enso.browser.toolbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import mozilla.components.browser.state.state.TabSessionState

// Pieces the cover's bottom bar and the inner sidebar share.

/** What the browser chrome shows about the current tab. */
data class PageState(
    val url: String = "",
    val title: String = "",
    val isSecure: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

fun TabSessionState.toPageState() = PageState(
    url = content.url,
    title = content.title,
    isSecure = content.securityInfo.isSecure,
    loading = content.loading,
    progress = content.progress,
    canGoBack = content.canGoBack,
    canGoForward = content.canGoForward,
)

/** The URL field while editing: focused with the keyboard up. Losing focus cancels the edit. */
@Composable
internal fun UrlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    accent: Color,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes placeholder: Int = R.string.address_placeholder,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                // Tapping the page moves focus away, which cancels the edit.
                if (it.isFocused) hadFocus = true else if (hadFocus) onCancel()
            },
        textStyle = EnsoType.Row,
        singleLine = true,
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit(value.text) }),
        decorationBox = { innerTextField ->
            if (value.text.isEmpty()) {
                Text(
                    stringResource(placeholder),
                    style = EnsoType.Row.copy(color = EnsoColors.TextSecondary),
                    maxLines = 1,
                )
            }
            innerTextField()
        },
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

@Composable
internal fun FieldButton(@DrawableRes icon: Int, @StringRes label: Int, size: Dp = 48.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(label),
            modifier = Modifier.size(22.dp),
            tint = EnsoColors.TextSecondary,
        )
    }
}

/** The inner sidebar's ⋮ menu, where Zen has its site settings button: Settings (the site's first) and Extensions. */
@Composable
internal fun OverflowMenu(
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    buttonSize: Dp = 48.dp,
    onOpenDownloads: (() -> Unit)? = null,
    onOpenReader: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(buttonSize)) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more),
                tint = EnsoColors.TextPrimary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = EnsoColors.Card,
            shadowElevation = 8.dp,
        ) {
            // Reader, on article pages: it shows the page as one, or while reading opens Reader's controls.
            onOpenReader?.let { open ->
                MenuRow(R.drawable.ic_reader, R.string.action_reader) {
                    expanded = false
                    open()
                }
            }
            // Downloads, only once something was downloaded recently or still is.
            onOpenDownloads?.let { open ->
                MenuRow(R.drawable.ic_download, R.string.downloads_title) {
                    expanded = false
                    open()
                }
            }
            MenuRow(R.drawable.ic_extensions, R.string.action_extensions) {
                expanded = false
                onOpenExtensions()
            }
            MenuRow(R.drawable.ic_settings, R.string.action_settings) {
                expanded = false
                onOpenSettings()
            }
        }
    }
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = EnsoType.MenuItem) },
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 200.dp)
            .height(48.dp),
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp)) },
        colors = MenuDefaults.itemColors(textColor = EnsoColors.TextPrimary, leadingIconColor = EnsoColors.TextPrimary),
    )
}

/** 2dp line in the space accent along the bottom bar's top edge, or the inner page card's. */
@Composable
internal fun LoadProgress(state: PageState, accent: Color, modifier: Modifier = Modifier) {
    val fraction = remember { Animatable(0f) }
    LaunchedEffect(state.progress) {
        val target = state.progress / 100f
        // Jump back when a new load starts instead of animating backwards.
        if (target < fraction.value) fraction.snapTo(target) else fraction.animateTo(target)
    }
    val alpha by animateFloatAsState(if (state.loading) 1f else 0f, label = "progressAlpha")
    Box(
        modifier
            .fillMaxWidth(fraction.value)
            .height(2.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(accent),
    )
}
