package app.enso.browser.bottombar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType

/**
 * Find in page (the actions sheet's Find on the cover, the ⋮ menu's unfolded): the field, which match is showing ("3/12", [match]), previous and next,
 * and ✕. Gecko highlights the matches as you type. The page stays undimmed above it, so they show.
 */
@Composable
internal fun FindBar(
    match: Pair<Int, Int>?,
    accent: Color,
    onFind: (String) -> Unit,
    onNext: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(EnsoColors.CurrentTab)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.find_placeholder), style = EnsoType.Row.copy(color = EnsoColors.TextSecondary))
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onFind(it)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = EnsoType.Row,
                    singleLine = true,
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
                )
            }
            if (text.isNotEmpty() && match != null) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.find_count, match.first.coerceAtMost(match.second), match.second), style = EnsoType.Secondary)
            }
        }
        FindButton(R.drawable.ic_chevron_up, R.string.find_previous) { onNext(false) }
        FindButton(R.drawable.ic_chevron_down, R.string.find_next) { onNext(true) }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PillFill)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_cancel), Modifier.size(16.dp), tint = EnsoColors.TextPrimary)
        }
    }
}

@Composable
private fun FindButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label), Modifier.size(22.dp), tint = EnsoColors.TextPrimary)
    }
}
