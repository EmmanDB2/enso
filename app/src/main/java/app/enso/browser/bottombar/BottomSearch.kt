package app.enso.browser.bottombar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.start.CircleIcon
import app.enso.browser.start.HistoryMatch
import app.enso.browser.start.PaletteRow
import app.enso.browser.start.matchingTabs
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.UrlEditor
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.displayTitle
import kotlinx.coroutines.delay

/**
 * Bottom search (board B4), Safari-style: the field at the top of a sheet that sits on the keyboard, then what's
 * worth switching to or opening. Before typing, the space's open Quick Tabs ([quickTabs], latest first). While
 * typing, matching open tabs, then history, then Google's suggestions in the rows that history leaves, so they take
 * over as the history thins out. The Quick Tab is made when a search is submitted, so ✕ leaves nothing behind.
 */
@Composable
internal fun SearchSheet(
    spaces: SpacesState,
    quickTabs: List<Tab>,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    mode: SearchMode,
    accent: Color,
    actions: BottomBarActions,
) {
    val text = query.text.trim()
    val tabs = remember(spaces, quickTabs, text) {
        (if (text.isEmpty()) quickTabs else matchingTabs(spaces, text)).take(MAX_TABS)
    }
    var history by remember { mutableStateOf(emptyList<HistoryMatch>()) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(text) {
        if (text.isEmpty()) {
            history = emptyList()
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(80) // Let typing settle before asking.
        val openUrls = tabs.map { it.url }.toSet()
        history = actions.history(text, limit = 8).filterNot { it.url in openUrls }.take(MAX_HISTORY)
        suggestions = actions.searchSuggestions(text).filterNot { it.equals(text, ignoreCase = true) }.take(MAX_ROWS - history.size)
    }

    Column(Modifier.fillMaxWidth()) {
        SearchField(query, onQueryChange, mode, accent, actions)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            if (tabs.isNotEmpty()) {
                SectionTitle(R.string.search_open_tabs)
                tabs.forEach { tab ->
                    PaletteRow(
                        leading = { Favicon(tab, size = 26.dp) },
                        title = tab.displayTitle(),
                        trailing = { Text(stringResource(R.string.palette_switch_to_tab), style = EnsoType.Secondary) },
                        onClick = { actions.select(tab.id) },
                    )
                }
            }
            if (history.isNotEmpty()) {
                SectionTitle(R.string.search_history)
                history.forEach { match ->
                    PaletteRow(
                        leading = { CircleIcon(R.drawable.ic_clock) },
                        title = if (match.title.isBlank()) {
                            AddressInput.displayHost(match.url)
                        } else {
                            stringResource(R.string.palette_history_row, match.title, AddressInput.displayHost(match.url))
                        },
                        onClick = { actions.submit(match.url, mode) },
                    )
                }
            }
            if (suggestions.isNotEmpty()) {
                SectionTitle(R.string.search_suggestions)
                suggestions.forEach { suggestion ->
                    PaletteRow(leading = { CircleIcon(R.drawable.ic_search) }, title = suggestion, onClick = { actions.submit(suggestion, mode) })
                }
            }
        }
    }
}

/** The field (search icon, the mic inside it) and ✕ beside it, which closes search. */
@Composable
private fun SearchField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    mode: SearchMode,
    accent: Color,
    actions: BottomBarActions,
) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(EnsoColors.CurrentTab)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                UrlEditor(
                    value = query,
                    onValueChange = onQueryChange,
                    accent = accent,
                    onSubmit = { if (it.isNotBlank()) actions.submit(it.trim(), mode) },
                    onCancel = { actions.showPanel(null) },
                    placeholder = R.string.search_placeholder,
                )
            }
            if (actions.voiceSearchAvailable) {
                IconButton(onClick = actions::voiceSearch) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.search_voice), Modifier.size(22.dp), tint = EnsoColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PillFill)
                .clickable { actions.showPanel(null) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_cancel), Modifier.size(18.dp), tint = EnsoColors.TextPrimary)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = EnsoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** At most this many open tabs. */
private const val MAX_TABS = 4

/** At most this many history rows; suggestions fill the rest of [MAX_ROWS]. */
private const val MAX_HISTORY = 4
private const val MAX_ROWS = 6
