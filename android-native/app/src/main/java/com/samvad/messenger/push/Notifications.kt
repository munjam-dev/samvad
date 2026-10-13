package com.samvad.messenger.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.samvad.messenger.MainActivity
import com.samvad.messenger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The conversation currently on screen; its notifications are suppressed. */
object ActiveChat {
  private val _id = MutableStateFlow<String?>(null)
  val id: StateFlow<String?> = _id.asStateFlow()
  fun set(conversationId: String?) { _id.value = conversationId }
}

object Notifications {
  const val CHANNEL_MESSAGES = "messages"
  const val EXTRA_CONVERSATION = "samvad.conversationId"
  const val EXTRA_OTHER = "samvad.otherUid"

  fun createChannels(context: Context) {
    val channel = NotificationChannel(
      CHANNEL_MESSAGES,
      context.getString(R.string.notif_channel_messages),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.notif_channel_messages_desc) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun canPost(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
      (android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

  /** One notification per conversation; a newer message replaces the older one. */
  fun showMessage(context: Context, conversationId: String, senderId: String, title: String, body: String) {
    if (!canPost(context) || ActiveChat.id.value == conversationId) return
    val open = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(EXTRA_CONVERSATION, conversationId)
      .putExtra(EXTRA_OTHER, senderId)
    val pending = PendingIntent.getActivity(
      context,
      conversationId.hashCode(),
      open,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(title.ifBlank { context.getString(R.string.notif_new_message) })
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(pending)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(conversationId.hashCode(), notification)
    } catch (_: SecurityException) {
      // Permission revoked between the check and the post; nothing to show.
    }
  }

  fun cancel(context: Context, conversationId: String) =
    NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
}
