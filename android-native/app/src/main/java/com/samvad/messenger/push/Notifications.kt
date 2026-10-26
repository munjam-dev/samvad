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
  const val CHANNEL_SECURITY = "security"
  const val CHANNEL_MOMENTS = "moments"
  const val EXTRA_MOMENTS = "samvad.moments"
  private const val SECURITY_ID = 0x5EC
  const val EXTRA_CONVERSATION = "samvad.conversationId"
  const val EXTRA_OTHER = "samvad.otherUid"
  const val EXTRA_GROUP = "samvad.group"
  /** A community announcement push also carries the community, so a tap opens the Announcements channel. */
  const val EXTRA_COMMUNITY = "samvad.community"

  fun createChannels(context: Context) {
    val channel = NotificationChannel(
      CHANNEL_MESSAGES,
      context.getString(R.string.notif_channel_messages),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.notif_channel_messages_desc) }
    val security = NotificationChannel(
      CHANNEL_SECURITY,
      context.getString(R.string.notif_channel_security),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.notif_channel_security_desc) }
    val moments = NotificationChannel(
      CHANNEL_MOMENTS,
      context.getString(R.string.notif_channel_moments),
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply { description = context.getString(R.string.notif_channel_moments_desc) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, security, moments))
  }

  /**
   * Someone asked to reset two-step verification. Only the owner's verified devices receive this;
   * opening the app records activity, which cancels the reset.
   */
  fun showSecurityAlert(context: Context) {
    if (!canPost(context)) return
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pending = PendingIntent.getActivity(context, SECURITY_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val body = context.getString(R.string.notif_two_step_reset)
    val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(context.getString(R.string.notif_security_title))
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(pending)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(SECURITY_ID, notification)
    } catch (_: SecurityException) {
      // Permission revoked between the check and the post.
    }
  }

  /**
   * Someone you chat with posted a moment. The push carries only their name; tapping opens the Moments tab. One
   * notification per author, so a second post replaces the first. [silent] is quiet hours.
   */
  fun showMoment(context: Context, authorId: String, authorName: String, showName: Boolean, silent: Boolean) {
    if (!canPost(context)) return
    val open = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(EXTRA_MOMENTS, true)
    val id = "moment:$authorId".hashCode()
    val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val body = if (showName && authorName.isNotBlank()) context.getString(R.string.notif_moment_new, authorName) else context.getString(R.string.notif_moment_generic)
    val public = NotificationCompat.Builder(context, CHANNEL_MOMENTS)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(context.getString(R.string.notif_moment_generic))
      .build()
    val notification = NotificationCompat.Builder(context, CHANNEL_MOMENTS)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(context.getString(R.string.nav_status))
      .setContentText(body)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setCategory(NotificationCompat.CATEGORY_SOCIAL)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setContentIntent(pending)
      .setSilent(silent)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(id, notification)
    } catch (_: SecurityException) {
      // Permission revoked between the check and the post; nothing to show.
    }
  }

  fun canPost(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
      (android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

  /**
   * One notification per conversation; a newer message replaces the older one. With [showPreview]
   * off, only "New message" is shown. On a secure lock screen only the public version (no sender,
   * no text) is shown, whatever the setting.
   */
  fun showMessage(
    context: Context,
    conversationId: String,
    senderId: String,
    title: String,
    body: String,
    showPreview: Boolean = true,
    group: Boolean = false,
    /** Quiet hours (Settings → Notifications): still delivered and visible, but no sound or vibration. */
    silent: Boolean = false,
    communityId: String? = null,
  ) {
    if (!canPost(context) || ActiveChat.id.value == conversationId) return
    val open = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(EXTRA_CONVERSATION, conversationId)
      .putExtra(EXTRA_OTHER, senderId)
      .putExtra(EXTRA_GROUP, group)
      .apply { if (communityId != null) putExtra(EXTRA_COMMUNITY, communityId) }
    val pending = PendingIntent.getActivity(
      context,
      conversationId.hashCode(),
      open,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val appName = context.getString(R.string.app_name)
    val newMessage = context.getString(R.string.notif_new_message)
    val shownTitle = if (showPreview) title.ifBlank { newMessage } else appName
    val shownBody = if (showPreview) body else newMessage
    val public = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(appName)
      .setContentText(newMessage)
      .build()
    val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(ContextCompat.getColor(context, R.color.notification_accent))
      .setContentTitle(shownTitle)
      .setContentText(shownBody)
      .setStyle(NotificationCompat.BigTextStyle().bigText(shownBody))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(pending)
      // Quiet hours: still delivered and shown, just without sound or vibration for this one notification.
      .setSilent(silent)
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
