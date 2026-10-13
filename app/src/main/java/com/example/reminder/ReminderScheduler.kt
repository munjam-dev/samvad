package com.example.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.model.ReminderEntity
import com.example.receiver.ReminderReceiver
import java.util.concurrent.TimeUnit

class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleReminder(
        reminder: ReminderEntity,
        documentTitle: String
    ) {
        val triggerTime = reminder.scheduledTimestamp
        // If trigger time is already in the past, skip scheduling
        if (triggerTime <= System.currentTimeMillis()) {
            return
        }

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_DOCUMENT_TITLE, documentTitle)
            putExtra(ReminderReceiver.EXTRA_DAYS_REMAINING, reminder.reminderDaysBefore)
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminder.notificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            // In Android 12+, exact alarm permission might not be granted; fallback to standard set()
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelReminder(notificationId: Int) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    companion object {
        fun calculateReminderTimestamps(expiryMillis: Long, daysBeforeList: List<Int>): List<Pair<Int, Long>> {
            return daysBeforeList.mapNotNull { days ->
                val offsetMillis = TimeUnit.DAYS.toMillis(days.toLong())
                val trigger = expiryMillis - offsetMillis
                if (trigger > System.currentTimeMillis()) {
                    Pair(days, trigger)
                } else null
            }
        }
    }
}
