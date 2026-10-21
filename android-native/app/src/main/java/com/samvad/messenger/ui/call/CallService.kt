package com.samvad.messenger.ui.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.samvad.messenger.R

/**
 * Foreground service for the time a call is connected. Its types (microphone, plus camera on a video call)
 * match what the call actually uses, which Android 14+ checks against the granted runtime permissions.
 * It is started only after the permissions are granted and only while the app is on screen or was opened by
 * the person tapping Accept, so the "started from background" limits do not apply.
 */
class CallService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
    val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
    ensureChannel(this)
    val open = PendingIntent.getActivity(
      this, 0, CallActivity.resumeIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val hangUp = PendingIntent.getBroadcast(
      this, 1, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val person = Person.Builder().setName(title.ifBlank { getString(R.string.app_name) }).setImportant(true).build()
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ONGOING)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(this, R.color.notification_accent))
      .setContentTitle(title.ifBlank { getString(R.string.app_name) })
      .setContentText(getString(R.string.call_notif_ongoing))
      .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setUsesChronometer(true)
      .setContentIntent(open)
      .build()
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    if (video && granted(this, Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    try {
      ServiceCompat.startForeground(this, ONGOING_ID, notification, if (Build.VERSION.SDK_INT >= 29) type else 0)
    } catch (_: Exception) {
      // The system refused a foreground start (for example a revoked permission): the call itself keeps running.
      stopSelf()
    }
    return START_NOT_STICKY
  }

  companion object {
    const val CHANNEL_ONGOING = "call_ongoing"
    const val ONGOING_ID = 0xCA11
    private const val ACTION_STOP = "samvad.call.STOP"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_VIDEO = "video"

    fun granted(context: Context, permission: String) =
      ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
      val nm = context.getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL_ONGOING) != null) return
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW)
          .apply { description = context.getString(R.string.call_channel_ongoing_desc) },
      )
    }

    fun start(context: Context, title: String, video: Boolean) {
      if (!granted(context, Manifest.permission.RECORD_AUDIO)) return
      runCatching {
        ContextCompat.startForegroundService(
          context, Intent(context, CallService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_VIDEO, video),
        )
      }
    }

    fun stop(context: Context) {
      runCatching { context.stopService(Intent(context, CallService::class.java)) }
    }
  }
}
