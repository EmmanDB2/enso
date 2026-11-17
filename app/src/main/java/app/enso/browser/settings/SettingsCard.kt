package app.enso.browser.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import kotlin.math.roundToInt

/** Which settings the inner screen's card shows. */
enum class SettingsPage { Site, All }

/**
 * A settings screen's heading: a back arrow when there's somewhere to go back to, then its title. The arrow sits on
 * the rows' left margin, and the title follows it, or starts on the margin when there's no arrow.
 */
@Composable
fun SettingsHeader(onBack: (() -> Unit)?, modifier: Modifier = Modifier, title: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            // A 48 dp button puts its 22 dp arrow 13 dp in; 3 dp more lines it up with the rows.
            IconButton(onClick = onBack, modifier = Modifier.padding(start = SettingsMargin - 13.dp)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.menu_back),
                    Modifier.size(22.dp),
                    tint = EnsoColors.TextPrimary,
                )
            }
        } else {
            Spacer(Modifier.width(SettingsMargin))
        }
        title()
    }
}

/** "All settings" as a heading. */
@Composable
fun AllSettingsTitle() {
    Text(stringResource(R.string.settings_all), style = SettingsType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The inner screen's settings card, from the sidebar's ⋮ menu: over the dimmed page card ([area], window pixels), as
 * the Extensions card is. A tap around it, or Back, closes it; [onBack] goes back a screen first when there's one.
 */
@Composable
fun SettingsCard(
    area: Rect,
    areaCorner: Dp,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    header: @Composable () -> Unit,
    cardModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack ?: onClose)
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
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.85f)
                    .heightIn(max = with(density) { area.height.toDp() } - 64.dp)
                    .shadow(24.dp, shape)
                    .background(EnsoColors.CurrentTab, shape)
                    .clip(shape)
                    // Taps on the card stay on it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 8.dp),
            ) {
                header()
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) { content() }
            }
        }
    }
}
