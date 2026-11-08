package app.fuckclip.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fuckclip.R
import app.fuckclip.service.LogKeepAliveService

@Composable
internal fun LogKeepAliveSetting() {
    val context = LocalContext.current
    val running by LogKeepAliveService.isRunning.collectAsStateWithLifecycle()
    var showNotificationSettings by rememberSaveable { mutableStateOf(false) }

    fun startKeepAlive() {
        if (!LogKeepAliveService.notificationsAvailable(context)) {
            showNotificationSettings = true
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, LogKeepAliveService::class.java))
        } catch (_: IllegalStateException) {
            Toast.makeText(context, R.string.log_keepalive_start_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.log_keepalive_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startKeepAlive() else showNotificationSettings = true
    }

    SettingsSwitchRow(
        icon = Icons.Default.Notifications,
        title = stringResource(R.string.settings_log_keepalive_title),
        subtitle = stringResource(R.string.settings_log_keepalive_summary),
        checked = running,
        onCheckedChange = { enable ->
            if (!enable) {
                context.stopService(Intent(context, LogKeepAliveService::class.java))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startKeepAlive()
            }
        }
    )

    if (showNotificationSettings) {
        AlertDialog(
            onDismissRequest = { showNotificationSettings = false },
            title = { Text(stringResource(R.string.settings_log_keepalive_title)) },
            text = { Text(stringResource(R.string.log_keepalive_notifications_required)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationSettings = false
                    context.startActivity(LogKeepAliveService.notificationSettingsIntent(context))
                }) { Text(stringResource(R.string.log_keepalive_open_notification_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationSettings = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
