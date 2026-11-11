package app.enso.browser.downloads

import app.enso.browser.permissions.PromptArea
import androidx.compose.ui.geometry.Rect
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.spacemenu.BlockSpaceMenu
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType

/** A download a site started, waiting on the user's Download or Cancel. */
class DownloadPrompt(val fileName: String, val size: Long?, val onDownload: () -> Unit, val onCancel: () -> Unit)

/**
 * The download prompt (step 12): the file's name and size, and Cancel or Download, above the cover's bar
 * ([bottomInset]). Back or a tap outside cancels. A plain first version; its look comes in a later UI pass.
 */
@Composable
fun DownloadPromptCard(prompt: DownloadPrompt, accent: Color, bottomInset: Dp, cardModifier: Modifier = Modifier, area: Rect? = null) {
    BackHandler(onBack = prompt.onCancel)
    BlockSpaceMenu()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(prompt) { detectTapGestures { prompt.onCancel() } },
    ) {
        PromptArea(area) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                cardModifier
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .shadow(16.dp, shape)
                    .background(EnsoColors.Card, shape)
                    .clip(shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.download_prompt_title), style = EnsoType.RowTitle.copy(fontSize = 17.sp))
                Text(prompt.fileName, style = EnsoType.Row, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                prompt.size?.takeIf { it > 0 }?.let { Text(Formatter.formatShortFileSize(LocalContext.current, it), style = EnsoType.Secondary) }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.download_prompt_cancel), EnsoColors.FieldFill, EnsoColors.TextPrimary, Modifier.weight(1f), prompt.onCancel)
                    Button(stringResource(R.string.download_prompt_download), accent, Color.White, Modifier.weight(1f), prompt.onDownload)
                }
            }
        }
    }
}

@Composable
private fun Button(label: String, fill: Color, text: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.height(44.dp).clip(shape).background(fill, shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = EnsoType.MenuItem.copy(color = text))
    }
}
