package com.samvad.messenger.tarang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.samvad.messenger.MainActivity
import com.samvad.messenger.R
import com.samvad.messenger.SamvadApplication

/**
 * Keeps the Bluetooth mesh running while the app is in the background. It exists only after the person turns
 * Tarang on, shows an ongoing notification with a "Stop Tarang" action, and ends when Tarang stops.
 */
class TarangService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      (application as SamvadApplication).container.tarang.stop()
      stopSelf()
      return START_NOT_STICKY
    }
    ensureChannel(this)
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
      this, 1, Intent(this, TarangService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.tarang_notif_title))
      .setContentText(getString(R.string.tarang_notif_text))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(open)
      .addAction(0, getString(R.string.tarang_stop), stop)
      .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    return START_NOT_STICKY
  }

  companion object {
    private const val CHANNEL = "tarang"
    private const val NOTIFICATION_ID = 7410
    private const val ACTION_STOP = "com.samvad.messenger.tarang.STOP"

    private fun ensureChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val channel = NotificationChannel(CHANNEL, context.getString(R.string.tarang_channel), NotificationManager.IMPORTANCE_LOW)
        .apply { description = context.getString(R.string.tarang_channel_desc) }
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, TarangService::class.java))
      } catch (_: Exception) {
        // Starting from the background can be refused; the mesh still runs while the app is open.
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, TarangService::class.java))
    }
  }
}
