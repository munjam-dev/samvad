package com.example.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.data.SanrakshaDatabase
import com.example.data.model.CategoryEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ReminderEntity
import com.example.reminder.ReminderScheduler
import com.example.security.CryptoVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DocumentVaultRepository(
    private val context: Context,
    private val database: SanrakshaDatabase,
    private val cryptoVaultManager: CryptoVaultManager
) {
    private val documentDao = database.documentDao()
    private val categoryDao = database.categoryDao()
    private val reminderDao = database.reminderDao()
    private val reminderScheduler = ReminderScheduler(context)

    // Flow queries
    val allDocuments: Flow<List<DocumentEntity>> = documentDao.getAllDocuments()
    val allCategories: Flow<List<CategoryEntity>> = categoryDao.getAllCategories()
    val allReminders: Flow<List<ReminderEntity>> = reminderDao.getAllReminders()
    val documentCount: Flow<Int> = documentDao.getDocumentCount()

    fun getRecentDocuments(limit: Int = 5): Flow<List<DocumentEntity>> =
        documentDao.getRecentDocuments(limit)

    fun getDocumentsByCategory(categoryId: String): Flow<List<DocumentEntity>> =
        documentDao.getDocumentsByCategory(categoryId)

    fun searchDocuments(query: String): Flow<List<DocumentEntity>> =
        documentDao.searchDocuments(query)

    fun getExpiringSoonCount(thresholdMillis: Long): Flow<Int> =
        documentDao.getExpiringSoonCount(thresholdMillis)

    suspend fun getDocumentById(id: String): DocumentEntity? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(id)
    }

    suspend fun getCategoryById(id: String): CategoryEntity? = withContext(Dispatchers.IO) {
        categoryDao.getCategoryById(id)
    }

    /**
     * Imports a document from a content URI, validates, encrypts using Keystore AES-GCM,
     * saves metadata into Room, and schedules optional reminders.
     */
    suspend fun importDocument(
        contentUri: Uri,
        title: String,
        categoryId: String,
        expiryDate: Long? = null,
        notes: String? = null,
        reminderDaysList: List<Int> = emptyList()
    ): Result<DocumentEntity> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val originalFileName = resolveFileName(contentUri) ?: "document_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(contentUri) ?: guessMimeType(originalFileName)

            val documentId = UUID.randomUUID().toString()
            val internalEncFileName = "$documentId.enc"

            // Encrypt directly from the content resolver input stream into app-private storage
            val encryptedFileSize = contentResolver.openInputStream(contentUri)?.use { inputStream ->
                cryptoVaultManager.encryptToFile(inputStream, internalEncFileName)
            } ?: throw IllegalStateException("Could not read source document content.")

            val document = DocumentEntity(
                id = documentId,
                title = title.ifBlank { originalFileName },
                categoryId = categoryId,
                internalFileReference = internalEncFileName,
                originalFileName = originalFileName,
                mimeType = mimeType,
                fileSizeBytes = encryptedFileSize,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                expiryDate = expiryDate,
                encryptionVersion = 1,
                notes = notes
            )

            documentDao.insertDocument(document)

            // Schedule reminders if expiry date is present
            if (expiryDate != null && expiryDate > System.currentTimeMillis() && reminderDaysList.isNotEmpty()) {
                val scheduledReminders = mutableListOf<ReminderEntity>()
                val remindersToSchedule = ReminderScheduler.calculateReminderTimestamps(expiryDate, reminderDaysList)
                for ((daysBefore, triggerTimestamp) in remindersToSchedule) {
                    val reminder = ReminderEntity(
                        documentId = documentId,
                        expiryDate = expiryDate,
                        reminderDaysBefore = daysBefore,
                        scheduledTimestamp = triggerTimestamp
                    )
                    scheduledReminders.add(reminder)
                    reminderScheduler.scheduleReminder(reminder, document.title)
                }
                if (scheduledReminders.isNotEmpty()) {
                    reminderDao.insertReminders(scheduledReminders)
                }
            }

            Result.success(document)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDocument(
        documentId: String,
        title: String,
        categoryId: String,
        expiryDate: Long?,
        notes: String?,
        reminderDaysList: List<Int>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.failure(IllegalStateException("Document not found"))

            val updated = existing.copy(
                title = title,
                categoryId = categoryId,
                expiryDate = expiryDate,
                notes = notes,
                updatedAt = System.currentTimeMillis()
            )
            documentDao.updateDocument(updated)

            // Cancel existing reminders and reschedule
            val existingReminders = reminderDao.getRemindersForDocument(documentId)
            existingReminders.forEach { reminderScheduler.cancelReminder(it.notificationId) }
            reminderDao.deleteRemindersForDocument(documentId)

            if (expiryDate != null && expiryDate > System.currentTimeMillis() && reminderDaysList.isNotEmpty()) {
                val newReminders = mutableListOf<ReminderEntity>()
                val remindersToSchedule = ReminderScheduler.calculateReminderTimestamps(expiryDate, reminderDaysList)
                for ((daysBefore, triggerTimestamp) in remindersToSchedule) {
                    val reminder = ReminderEntity(
                        documentId = documentId,
                        expiryDate = expiryDate,
                        reminderDaysBefore = daysBefore,
                        scheduledTimestamp = triggerTimestamp
                    )
                    newReminders.add(reminder)
                    reminderScheduler.scheduleReminder(reminder, updated.title)
                }
                if (newReminders.isNotEmpty()) {
                    reminderDao.insertReminders(newReminders)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes single document, cancels its reminders, and deletes encrypted storage file.
     */
    suspend fun deleteDocument(documentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val doc = documentDao.getDocumentById(documentId)
            if (doc != null) {
                val reminders = reminderDao.getRemindersForDocument(documentId)
                reminders.forEach { reminderScheduler.cancelReminder(it.notificationId) }
                reminderDao.deleteRemindersForDocument(documentId)
                cryptoVaultManager.deleteEncryptedFile(doc.internalFileReference)
                documentDao.deleteDocumentById(documentId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Wipes all documents, reminders, and clears the encrypted vault entirely.
     */
    suspend fun deleteAllDocuments(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val reminders = database.reminderDao().getDueReminders(Long.MAX_VALUE)
            reminders.forEach { reminderScheduler.cancelReminder(it.notificationId) }
            reminderDao.deleteAllReminders()
            documentDao.deleteAllDocuments()
            cryptoVaultManager.clearEntireVault()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypts document content safely into memory for ephemeral preview.
     */
    suspend fun getDecryptedBytes(document: DocumentEntity): ByteArray = withContext(Dispatchers.IO) {
        cryptoVaultManager.decryptToByteArray(document.internalFileReference)
    }

    /**
     * Decrypts document content to an ephemeral cache file for PDF rendering.
     */
    suspend fun getDecryptedCacheFile(document: DocumentEntity): File = withContext(Dispatchers.IO) {
        val extension = if (document.mimeType == "application/pdf") "pdf" else "img"
        cryptoVaultManager.decryptToTemporaryCacheFile(document.internalFileReference, extension, isShared = false)
    }

    /**
     * Decrypts document to a temporary file in shared folder and returns a secure FileProvider Uri.
     */
    suspend fun prepareDocumentForExport(document: DocumentEntity): Uri = withContext(Dispatchers.IO) {
        val extension = when (document.mimeType) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "bin"
        }
        val sharedFile = cryptoVaultManager.decryptToTemporaryCacheFile(document.internalFileReference, extension, isShared = true)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sharedFile
        )
    }

    suspend fun addCustomCategory(name: String, iconKey: String = "folder"): Result<CategoryEntity> = withContext(Dispatchers.IO) {
        try {
            val id = "custom_${System.currentTimeMillis()}"
            val category = CategoryEntity(
                id = id,
                name = name.trim(),
                iconKey = iconKey,
                isDefault = false
            )
            categoryDao.insertCategory(category)
            Result.success(category)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun guessMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
