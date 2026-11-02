package app.enso.browser.permissions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.enso.browser.MainActivity
import app.enso.browser.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import mozilla.components.concept.engine.webnotifications.WebNotification
import mozilla.components.concept.engine.webnotifications.WebNotificationDelegate

/**
 * Sites' notifications as Android notifications (step 11), for sites the user allowed. Gecko only sends them while
 * the site is open in a tab: notifications from closed sites would need a push service. Tapping one opens Ensō, which
 * hands the tap back to the page ([EXTRA_WEB_NOTIFICATION]).
 */
class SiteNotifications(private val context: Context) : WebNotificationDelegate {
    private val manager = NotificationManagerCompat.from(context)

    override fun onShowNotification(webNotification: WebNotification): Deferred<Boolean> {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return CompletableDeferred(false)
        ensureChannel()
        val tap = Intent(context, MainActivity::class.java)
            .setAction(ACTION_WEB_NOTIFICATION)
            .putExtra(EXTRA_WEB_NOTIFICATION, webNotification.engineNotification)
            .putExtra(EXTRA_WEB_NOTIFICATION_URL, webNotification.sourceUrl)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(webNotification.title)
            .setContentText(webNotification.body)
            .setSubText(webNotification.sourceUrl?.let { android.net.Uri.parse(it).host })
            .setStyle(NotificationCompat.BigTextStyle().bigText(webNotification.body))
            .setWhen(webNotification.timestamp)
            .setShowWhen(true)
            .setSilent(webNotification.silent)
            .setAutoCancel(!webNotification.requireInteraction)
            .setContentIntent(PendingIntent.getActivity(context, idOf(webNotification), tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        @Suppress("MissingPermission") // Checked above.
        manager.notify(idOf(webNotification), notification)
        return CompletableDeferred(true)
    }

    override fun onCloseNotification(webNotification: WebNotification) {
        manager.cancel(idOf(webNotification))
    }

    /** A site's notifications with the same tag replace each other, as on the web. */
    private fun idOf(notification: WebNotification) = (notification.sourceUrl + "#" + notification.tag).hashCode()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.site_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "site_notifications"

        /** A tapped site notification, for [MainActivity]: Gecko's notification, and the page it came from. */
        const val ACTION_WEB_NOTIFICATION = "app.enso.browser.WEB_NOTIFICATION"
        const val EXTRA_WEB_NOTIFICATION = "enso_web_notification"
        const val EXTRA_WEB_NOTIFICATION_URL = "enso_web_notification_url"
    }
}
