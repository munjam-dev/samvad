package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val categoryId: String,
    val internalFileReference: String, // Stored encrypted in context.filesDir/vault/{UUID}.enc
    val originalFileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val expiryDate: Long? = null, // epoch millis or null
    val encryptionVersion: Int = 1, // 1 = AES-256-GCM Keystore
    val notes: String? = null
)
