package app.enso.browser.toolbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import mozilla.components.browser.state.state.TabSessionState

/** What the top bar shows about the current tab. */
data class TopBarState(
    val url: String = "",
    val isSecure: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

fun TabSessionState.toTopBarState() = TopBarState(
    url = content.url,
    isSecure = content.securityInfo.isSecure,
    loading = content.loading,
    progress = content.progress,
    canGoBack = content.canGoBack,
    canGoForward = content.canGoForward,
)

/** Cover-screen top bar (boards 1 and 2): address field, ⋮ menu and load progress. */
@Composable
fun TopBar(
    state: TopBarState,
    editing: Boolean,
    statusBarInset: Dp,
    onEditingChange: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val accent = EnsoColors.DefaultSpaceAccent
    MaterialTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(EnsoColors.spaceTint(accent))
                .padding(top = statusBarInset),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AddressField(
                    state = state,
                    editing = editing,
                    accent = accent,
                    onEditingChange = onEditingChange,
                    onSubmit = onSubmit,
                    onReload = onReload,
                    onStop = onStop,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(2.dp))
                OverflowMenu(state, onBack, onForward)
            }
            LoadProgress(state, accent, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun AddressField(
    state: TopBarState,
    editing: Boolean,
    accent: Color,
    onEditingChange: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = AddressInput.displayHost(state.url)
    // Reset to the full URL, selected, each time editing starts.
    var input by remember(editing) {
        val text = if (host.isEmpty()) "" else state.url
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }

    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(EnsoColors.FieldFill)
            .clickable(enabled = !editing) { onEditingChange(true) }
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val leadingIcon = when {
            editing || host.isEmpty() -> R.drawable.ic_search
            state.isSecure -> R.drawable.ic_lock
            else -> R.drawable.ic_lock_open
        }
        Icon(
            painterResource(leadingIcon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = EnsoColors.TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (editing) {
                UrlEditor(
                    value = input,
                    onValueChange = { input = it },
                    accent = accent,
                    onSubmit = onSubmit,
                    onCancel = { onEditingChange(false) },
                )
            } else {
                Text(
                    text = host.ifEmpty { stringResource(R.string.address_placeholder) },
                    style = if (host.isEmpty()) EnsoType.Row.copy(color = EnsoColors.TextSecondary) else EnsoType.Row,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            editing -> if (input.text.isNotEmpty()) {
                FieldButton(R.drawable.ic_close, R.string.action_clear) { input = TextFieldValue() }
            }
            host.isEmpty() -> Unit
            state.loading -> FieldButton(R.drawable.ic_close, R.string.action_stop, onStop)
            else -> FieldButton(R.drawable.ic_reload, R.string.action_reload, onReload)
        }
    }
}

@Composable
private fun UrlEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    accent: Color,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
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
                    stringResource(R.string.address_placeholder),
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
private fun FieldButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(label),
            modifier = Modifier.size(22.dp),
            tint = EnsoColors.TextSecondary,
        )
    }
}

@Composable
private fun OverflowMenu(state: TopBarState, onBack: () -> Unit, onForward: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
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
            containerColor = Color.White,
            shadowElevation = 8.dp,
        ) {
            MenuRow(R.drawable.ic_arrow_left, R.string.menu_back, state.canGoBack) {
                expanded = false
                onBack()
            }
            MenuRow(R.drawable.ic_arrow_right, R.string.menu_forward, state.canGoForward) {
                expanded = false
                onForward()
            }
        }
    }
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    val disabled = EnsoColors.TextPrimary.copy(alpha = 0.38f)
    DropdownMenuItem(
        text = { Text(stringResource(label), style = EnsoType.MenuItem) },
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 200.dp)
            .height(48.dp),
        enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp)) },
        colors = MenuDefaults.itemColors(
            textColor = EnsoColors.TextPrimary,
            leadingIconColor = EnsoColors.TextPrimary,
            disabledTextColor = disabled,
            disabledLeadingIconColor = disabled,
        ),
    )
}

/** 2dp line in the space accent along the bar's bottom edge. */
@Composable
private fun LoadProgress(state: TopBarState, accent: Color, modifier: Modifier = Modifier) {
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
