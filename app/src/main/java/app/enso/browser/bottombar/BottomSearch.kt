package app.enso.browser.bottombar

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import app.enso.browser.start.CircleIcon
import app.enso.browser.start.HistoryMatch
import app.enso.browser.start.PaletteRow
import app.enso.browser.start.SwitchToTabChip
import app.enso.browser.start.matchingTabs
import app.enso.browser.toolbar.AddressInput
import app.enso.browser.toolbar.UrlEditor
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.LetterFavicon
import app.enso.browser.ui.displayTitle
import kotlinx.coroutines.delay
import mozilla.components.support.ktx.util.URLStringUtils

/**
 * Bottom search's suggestions (board B4), above the field. The most relevant sit closest to the thumb: the web
 * search just above the field, then open tabs to switch to, then history at the top.
 */
@Composable
internal fun SearchSuggestions(spaces: SpacesState, query: String, mode: SearchMode, actions: BottomBarActions) {
    var history by remember { mutableStateOf(emptyList<HistoryMatch>()) }
    val tabs = remember(spaces, query) { matchingTabs(spaces, query) }
    LaunchedEffect(query) {
        delay(80) // Let typing settle before querying.
        val openUrls = tabs.map { it.url }.toSet()
        history = actions.history(query.trim(), limit = 8).filterNot { it.url in openUrls }.take(if (query.isBlank()) 2 else 4)
    }
    // When the keyboard leaves too little room, the list scrolls, starting from the bottom: the best matches stay
    // in view.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 8.dp)) {
        history.asReversed().forEach { match ->
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
        tabs.asReversed().forEach { tab ->
            PaletteRow(
                leading = {
                    LetterFavicon(tab.title, tab.url, size = 26.dp, ring = if (tab.quick != null) EnsoColors.QuickTabOutlineOnLight else null)
                },
                title = tab.displayTitle(),
                trailing = { SwitchToTabChip() },
                onClick = { actions.select(tab.id) },
            )
        }
        val text = query.trim()
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(PillFill)) {
            PaletteRow(
                leading = { CircleIcon(R.drawable.ic_search) },
                title = if (text.isNotEmpty() && URLStringUtils.isURLLike(text)) {
                    stringResource(R.string.palette_go_to, AddressInput.displayHost(URLStringUtils.toNormalizedURL(text)))
                } else {
                    stringResource(R.string.palette_search_web)
                },
                onClick = { if (text.isNotEmpty()) actions.submit(text, mode) },
            )
        }
        Spacer(Modifier.height(12.dp))
        BarDivider()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The bar's row during bottom search: the field (tagged "Quick Tab" when what's typed opens one) and Cancel.
 * The keyboard pushes the whole surface up.
 */
@Composable
internal fun SearchRow(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    mode: SearchMode,
    accent: Color,
    actions: BottomBarActions,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(BarMetrics.Row).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(EnsoColors.CurrentTab)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp), tint = EnsoColors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                UrlEditor(
                    value = query,
                    onValueChange = onQueryChange,
                    accent = accent,
                    onSubmit = { if (it.isNotBlank()) actions.submit(it.trim(), mode) },
                    onCancel = { actions.showPanel(null) },
                )
            }
            if (mode == SearchMode.NewQuickTab) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).background(EnsoColors.QuickTabOutlineOnLight, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.search_quick_tab_tag),
                    style = EnsoType.Row.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = EnsoColors.TextSecondary),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.search_cancel),
            style = EnsoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { actions.showPanel(null) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}
