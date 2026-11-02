package app.enso.browser.bottombar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.extensions.ExtensionListActions
import app.enso.browser.extensions.ExtensionRow
import app.enso.browser.extensions.ExtensionsList
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import kotlinx.coroutines.delay

/**
 * What the bottom bar shows of extensions: the installed ones for the Extensions panel, and while an extension's
 * popup or settings page is open, its [pageTitle] and the pages captured behind it ([backdrop]). [onPageArea] gets
 * where the page goes, in window pixels, or null when it's gone.
 */
class BarExtensions(
    val rows: List<ExtensionRow>,
    val actions: ExtensionListActions,
    val pageTitle: String?,
    val backdrop: List<Pair<ImageBitmap, Rect>>,
    val onPageArea: (Rect?) -> Unit,
)

/** The Extensions panel, from the actions sheet: the installed extensions, with their switches. */
@Composable
internal fun ExtensionsPanel(extensions: BarExtensions, maxHeight: Dp, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PanelHeader(stringResource(R.string.extensions_title), onBack)
        ExtensionsList(extensions.rows, extensions.actions, Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()))
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * An extension's popup or settings page, in the bar's surface. The page is a GeckoView under the overlay, as
 * Glance's is: this leaves a box for it, which the bar clears so the page shows through, and reports where it is
 * once the panel has finished growing.
 */
@Composable
internal fun ExtensionPagePanel(title: String, height: Dp, onBack: () -> Unit, onPageArea: (Rect?) -> Unit) {
    val report by rememberUpdatedState(onPageArea)
    var area by remember { mutableStateOf<Rect?>(null) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PANEL_GROWS_MS)
        settled = true
    }
    LaunchedEffect(settled, area) { if (settled) report(area) }
    DisposableEffect(Unit) { onDispose { report(null) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PanelHeader(title, onBack)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .onGloballyPositioned { area = it.boundsInWindow() },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PanelHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.menu_back), tint = EnsoColors.TextPrimary)
        }
        Text(
            title,
            style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** About how long the bar's surface takes to grow into a panel. */
private const val PANEL_GROWS_MS = 350L
