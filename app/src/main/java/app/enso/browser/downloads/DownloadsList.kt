package app.enso.browser.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.enso.browser.R
import app.enso.browser.ui.EnsoColors
import app.enso.browser.ui.EnsoType

/** "Downloads", heading the unfolded Downloads card. */
@Composable
fun DownloadsTitle() {
    Text(stringResource(R.string.downloads_title), style = EnsoType.RowTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
}

/** A download as the Downloads page shows it. */
data class DownloadItem(
    val id: String,
    val fileName: String,
    val size: Long?,
    val createdAt: Long,
    val status: Status,
    /** How far it has got, 0 to 1, or null while the size is unknown. */
    val progress: Float?,
) {
    enum class Status { Running, Paused, Done, Failed, Cancelled }
}

/** What the Downloads page asks the app to do. */
interface DownloadListActions {
    /** Opens a finished download's file in another app. */
    fun open(id: String)

    /** Starts a failed or cancelled download again. */
    fun retry(id: String)

    /** Takes a download off the list; its file stays. */
    fun remove(id: String)

    /** Android's own list of the Downloads folder. */
    fun openFolder()
}

/**
 * The Downloads page's list (polish pass), newest first: each file with its type, size and day, or how it's going.
 * Tapping a finished one opens it, and a failed or cancelled one starts again. A swipe takes it off the list but
 * keeps the file. "Open Downloads folder" ends it.
 */
@Composable
fun DownloadsList(items: List<DownloadItem>, actions: DownloadListActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.downloads_empty),
                style = EnsoType.Secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
        items.forEach { item -> key(item.id) { DownloadRow(item, actions) } }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(EnsoColors.FieldFill)
                .clickable(onClick = actions::openFolder),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, Modifier.size(20.dp), tint = EnsoColors.TextPrimary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.downloads_open_folder), style = EnsoType.Row)
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, actions: DownloadListActions) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) actions.remove(item.id)
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(swipe, backgroundContent = {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    when (item.status) {
                        DownloadItem.Status.Done -> actions.open(item.id)
                        DownloadItem.Status.Failed, DownloadItem.Status.Cancelled -> actions.retry(item.id)
                        else -> {}
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTile(item.fileName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = EnsoType.Row, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(subtitle(item), style = EnsoType.Secondary.copy(fontSize = 13.sp, color = if (item.status == DownloadItem.Status.Failed) EnsoColors.CloseMenuText else EnsoColors.TextSecondary), maxLines = 1)
            }
        }
    }
}

/** The file's type as a short label on a tile: PDF, IMG, ZIP and so on. */
@Composable
private fun FileTile(fileName: String) {
    Box(
        Modifier.size(width = 34.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(EnsoColors.FieldFill),
        contentAlignment = Alignment.Center,
    ) {
        Text(typeLabel(fileName), style = EnsoType.Secondary.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EnsoColors.TextPrimary))
    }
}

private fun typeLabel(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp" -> "IMG"
        "mp4", "mkv", "webm", "mov", "avi" -> "VID"
        "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus" -> "AUD"
        "zip", "rar", "7z", "tar", "gz", "xz" -> "ZIP"
        "doc", "docx", "odt", "rtf" -> "DOC"
        "" -> "FILE"
        else -> extension.take(4).uppercase()
    }
}

@Composable
private fun subtitle(item: DownloadItem): String {
    val context = LocalContext.current
    val size = item.size?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    return when (item.status) {
        DownloadItem.Status.Running -> item.progress?.let { stringResource(R.string.download_running_percent, (it * 100).toInt()) }
            ?: stringResource(R.string.download_running)
        DownloadItem.Status.Paused -> stringResource(R.string.download_paused)
        DownloadItem.Status.Failed -> stringResource(R.string.download_failed)
        DownloadItem.Status.Cancelled -> stringResource(R.string.download_cancelled)
        DownloadItem.Status.Done -> {
            val day = DateUtils.getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
            listOfNotNull(size, day).joinToString(" · ")
        }
    }
}
