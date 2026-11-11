package app.enso.browser.bottombar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.enso.browser.R
import app.enso.browser.downloads.DownloadItem
import app.enso.browser.downloads.DownloadListActions
import app.enso.browser.downloads.DownloadsList
import app.enso.browser.reader.ReaderControls
import app.enso.browser.reader.ReaderControlsContent

/**
 * What the bottom bar shows of downloads (polish pass): the Downloads page's [items], and what the chevron does.
 * For a couple of seconds after a download starts ([justStarted]) the chevron is a download button that opens the
 * Downloads page; after that a ring around it shows the newest running download's [progress] (null while its size is
 * unknown, when an arc goes round instead). [recent] puts the Downloads button in the actions sheet.
 */
class BarDownloads(
    val items: List<DownloadItem>,
    val actions: DownloadListActions,
    val justStarted: Boolean,
    val running: Boolean,
    val progress: Float?,
    val recent: Boolean,
)

/**
 * What the bar shows of Reader (polish pass): whether the page on screen can be read as an article ([available]),
 * whether it's showing as one ([active]), and the appearance controls, which [onExit] and [onColorsChosen] go with.
 */
class BarReader(
    val available: Boolean,
    val active: Boolean,
    val controls: ReaderControls,
    val onExit: () -> Unit,
    val onColorsChosen: () -> Unit,
)

/** The Reader panel, from the Reader tile while reading: the appearance controls and Exit Reader. */
@Composable
internal fun ReaderPanel(reader: BarReader, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp)) { PanelHeader(stringResource(R.string.reader_title), onBack) }
        ReaderControlsContent(reader.controls, reader.onExit, reader.onColorsChosen)
        Spacer(Modifier.height(10.dp))
    }
}

/** The Downloads page, from the chevron as a download starts or the actions sheet's Downloads button. */
@Composable
internal fun DownloadsPanel(downloads: BarDownloads, maxHeight: Dp, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp)) { PanelHeader(stringResource(R.string.downloads_title), onBack) }
        DownloadsList(downloads.items, downloads.actions, Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()))
        Spacer(Modifier.height(16.dp))
    }
}

/** The ring around the chevron while a download runs: filling as it goes, or an arc going round when its size is unknown. */
@Composable
internal fun DownloadRing(progress: Float?, color: Color, modifier: Modifier) {
    val spin by rememberInfiniteTransition(label = "downloadRing").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "spin",
    )
    val shown by animateFloatAsState(progress ?: 0f, label = "downloadProgress")
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
        if (progress != null) {
            drawArc(color, -90f, 360f * shown, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawArc(color, spin - 90f, 90f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
