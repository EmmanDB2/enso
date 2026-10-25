package app.enso.browser.bottombar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.images.ImageLoadRequest

/**
 * A tab's page thumbnail, from the captures Android Components saves as each page loads on the cover, or
 * [placeholder] until the tab has one.
 */
@Composable
internal fun TabThumbnail(
    tabId: String,
    storage: ThumbnailStorage,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, tabId) {
        value = storage.loadThumbnail(ImageLoadRequest(tabId, THUMBNAIL_SIZE_PX, isPrivate = false)).await()?.asImageBitmap()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = contentScale, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
        } else {
            placeholder()
        }
    }
}

/** The size thumbnails load at: a Quick Tab card's width on the cover. */
private const val THUMBNAIL_SIZE_PX = 720
