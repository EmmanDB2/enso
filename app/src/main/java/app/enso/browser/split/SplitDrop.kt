package app.enso.browser.split

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.spaces.Tab
import app.enso.browser.tabsheet.quickTabOutline
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType
import app.enso.browser.ui.Favicon
import app.enso.browser.ui.displayTitle
import kotlin.math.roundToInt

/** A tab being dragged out of the sidebar or rail, and where the finger is in the window. */
data class TabDrag(val tabId: String, val finger: Offset)

/** The right half of the page card while a tab is dragged: dropping it there splits it with the page. */
@Composable
fun SplitDropZone(zone: Rect, hovered: Boolean, accent: Color, corner: Dp) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .offset { IntOffset(zone.left.roundToInt(), zone.top.roundToInt()) }
            .size(with(density) { zone.width.toDp() }, with(density) { zone.height.toDp() })
            .background(accent.copy(alpha = if (hovered) 0.24f else 0.1f), shape)
            .border(2.dp, accent.copy(alpha = if (hovered) 1f else 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_split), contentDescription = null, Modifier.size(36.dp), tint = accent)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.split_drop_here), style = EnsoType.RowTitle.copy(color = accent))
        }
    }
}

/** The dragged tab, as a lifted white row just above the finger. */
@Composable
fun DraggedTab(tab: Tab, finger: Offset) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(14.dp)
    val width = with(density) { DragWidth.toPx() }
    val lift = with(density) { (DragHeight + 16.dp).toPx() }
    Row(
        Modifier
            .offset { IntOffset((finger.x - width / 2).roundToInt(), (finger.y - lift).roundToInt()) }
            .width(DragWidth)
            .height(DragHeight)
            .shadow(10.dp, shape)
            .background(EnsoColors.CurrentTab, shape)
            .quickTabOutline(tab, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Favicon(tab)
        Spacer(Modifier.width(12.dp))
        Text(tab.displayTitle(), style = EnsoType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val DragWidth = 220.dp
private val DragHeight = 44.dp
