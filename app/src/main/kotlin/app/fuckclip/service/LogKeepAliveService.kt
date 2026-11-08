package app.fuckclip.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.fuckclip.R
import app.fuckclip.i18n.AppLanguage
import app.fuckclip.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the process that receives and stores audit events in the foreground. */
class LogKeepAliveService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.settings_log_keepalive_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )

            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                this, 0,
                Intent(this, LogKeepAliveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_log_keepalive)
                .setContentTitle(getString(R.string.log_keepalive_notification_title))
                .setContentText(getString(R.string.log_keepalive_notification_body))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0, getString(R.string.log_keepalive_stop), stop)
                .build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            if (!notificationsAvailable(this)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            running.value = true
            return START_STICKY
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not start audit log foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LogKeepAlive"
        private const val CHANNEL_ID = "audit_log_keepalive"
        private const val NOTIFICATION_ID = 102
        private const val ACTION_STOP = "app.fuckclip.action.STOP_LOG_KEEPALIVE"

        private val running = MutableStateFlow(false)
        internal val isRunning = running.asStateFlow()

        internal fun notificationsAvailable(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            return channel?.importance != NotificationManager.IMPORTANCE_NONE
        }

        internal fun notificationSettingsIntent(context: Context): Intent {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            val channelBlocked = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                channel?.importance == NotificationManager.IMPORTANCE_NONE
            return if (channelBlocked) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
    }
}
