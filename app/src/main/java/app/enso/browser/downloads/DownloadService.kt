package app.enso.browser.downloads

import androidx.core.app.NotificationManagerCompat
import app.enso.browser.EnsoApplication
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider

/**
 * Downloads files in the background (step 12), with Android's notification for progress; tapping it when done opens
 * the file. Android Components does the work: this only hands it Ensō's store, HTTP client and file helpers.
 */
class DownloadService : AbstractFetchDownloadService() {
    private val components get() = (application as EnsoApplication).components

    override val store get() = components.store
    override val httpClient get() = components.fetchClient
    override val downloadFileUtils get() = components.downloadFileUtils
    override val notificationsDelegate by lazy { NotificationsDelegate(NotificationManagerCompat.from(this)) }
    override val packageNameProvider by lazy { DefaultPackageNameProvider(this) }
    override val fileSizeFormatter by lazy { DefaultFileSizeFormatter(this) }
    override val downloadEstimator by lazy { DownloadEstimator(DefaultDateTimeProvider()) }
    override val downloadFileWriter by lazy { DefaultDownloadFileWriter(this, downloadFileUtils) }
}
