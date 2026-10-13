package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class ReminderEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val documentId: String,
    val expiryDate: Long,
    val reminderDaysBefore: Int, // 30, 7, or 1
    val scheduledTimestamp: Long, // exact alarm trigger timestamp
    val isTriggered: Boolean = false,
    val notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
)
