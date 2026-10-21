package com.samvad.messenger.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.samvad.core.call.CallKind
import com.samvad.messenger.MainActivity
import com.samvad.messenger.R
import com.samvad.messenger.ui.call.CallActionReceiver
import com.samvad.messenger.ui.call.CallActivity

/**
 * Incoming and missed call notifications. The ringtone and vibration belong to the notification channel, so
 * the system's Do Not Disturb, silent mode and per-app settings decide whether the phone rings.
 */
object CallNotifications {
  const val CHANNEL_CALLS = "calls"
  const val RING_MS = 45_000L

  private fun idOf(callId: String) = 0xC000 + (callId.hashCode() and 0xFFF)

  fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_CALLS) != null) return
    val channel = NotificationChannel(CHANNEL_CALLS, context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
      description = context.getString(R.string.call_channel_incoming_desc)
      setSound(
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
      )
      enableVibration(true)
      vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    nm.createNotificationChannel(channel)
  }

  /** True while the app is on screen: the incoming-call screen opens straight away instead. */
  fun appInForeground(): Boolean =
    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

  fun showIncoming(context: Context, callId: String, callerUid: String, callerName: String, kind: CallKind) {
    if (!Notifications.canPost(context)) {
      // Without notification permission a ring can still reach the person when the app is open.
      if (appInForeground()) runCatching { context.startActivity(CallActivity.incomingIntent(context, callId, callerUid, callerName, kind)) }
      return
    }
    ensureChannel(context)
    val full = PendingIntent.getActivity(
      context, idOf(callId), CallActivity.incomingIntent(context, callId, callerUid, callerName, kind),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val accept = PendingIntent.getActivity(
      context, idOf(callId) + 1, CallActivity.incomingIntent(context, callId, callerUid, callerName, kind, accept = true),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val decline = PendingIntent.getBroadcast(
      context, idOf(callId) + 2,
      Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE).putExtra(CallActionReceiver.EXTRA_CALL_ID, callId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val person = Person.Builder().setName(callerName).setImportant(true).build()
    val text = context.getString(if (kind == CallKind.VIDEO) R.string.call_incoming_video else R.string.call_incoming_voice)
    val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(callerName)
      .setContentText(text)
      .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, accept))
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setTimeoutAfter(RING_MS)
      .setFullScreenIntent(full, true)
      .setContentIntent(full)
      .build()
    // Keep ringing until answered, declined or timed out.
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    try {
      NotificationManagerCompat.from(context).notify(idOf(callId), notification)
    } catch (_: SecurityException) {
      return
    }
    if (appInForeground()) runCatching { context.startActivity(CallActivity.incomingIntent(context, callId, callerUid, callerName, kind)) }
  }

  fun cancelIncoming(context: Context, callId: String) = NotificationManagerCompat.from(context).cancel(idOf(callId))

  /** The ring ended without an answer: stop ringing and leave a quiet "Missed call" line. */
  fun showMissed(context: Context, callId: String, callerUid: String, callerName: String, kind: CallKind) {
    cancelIncoming(context, callId)
    if (!Notifications.canPost(context)) return
    val open = PendingIntent.getActivity(
      context, idOf(callId) + 3,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = context.getString(if (kind == CallKind.VIDEO) R.string.call_missed_video else R.string.call_missed_voice)
    val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MESSAGES)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(callerName)
      .setContentText(text)
      .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setAutoCancel(true)
      .setContentIntent(open)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(idOf(callId), notification)
    } catch (_: SecurityException) {
      // Permission revoked between the check and the post.
    }
  }
}
