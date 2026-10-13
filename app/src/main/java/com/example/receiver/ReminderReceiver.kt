package com.example.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SanrakshaApplication

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val documentTitle = intent.getStringExtra(EXTRA_DOCUMENT_TITLE) ?: "Your document"
        val daysRemaining = intent.getIntExtra(EXTRA_DAYS_REMAINING, 0)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = when {
            daysRemaining <= 0 -> "\"$documentTitle\" has reached its expiry date today."
            daysRemaining == 1 -> "\"$documentTitle\" expires tomorrow. Please take action."
            else -> "\"$documentTitle\" expires in $daysRemaining days."
        }

        val notification = NotificationCompat.Builder(context, SanrakshaApplication.CHANNEL_EXPIRY_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Document Expiry Alert • Sanraksha")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    companion object {
        const val EXTRA_DOCUMENT_TITLE = "extra_document_title"
        const val EXTRA_DAYS_REMAINING = "extra_days_remaining"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
