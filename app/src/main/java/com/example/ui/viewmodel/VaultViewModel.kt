package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SanrakshaApplication
import com.example.data.model.CategoryEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    TITLE_A_Z,
    EXPIRY_NEAREST
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as SanrakshaApplication).repository

    val documentCount: StateFlow<Int> = repository.documentCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val expiringSoonCount: StateFlow<Int> = repository.getExpiringSoonCount(
        System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categories: StateFlow<List<CategoryEntity>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDocuments: StateFlow<List<DocumentEntity>> = repository.getRecentDocuments(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReminders: StateFlow<List<ReminderEntity>> = repository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search and Filtering State
    val searchQuery = MutableStateFlow("")
    val selectedCategoryFilter = MutableStateFlow<String?>(null) // null = all
    val selectedMimeFilter = MutableStateFlow<String?>(null) // "pdf", "image", or null
    val sortOrder = MutableStateFlow(SortOrder.NEWEST_FIRST)
    val isGridView = MutableStateFlow(false)

    // Filtered documents flow for Library Screen
    val filteredDocuments: StateFlow<List<DocumentEntity>> = combine(
        repository.allDocuments,
        searchQuery,
        selectedCategoryFilter,
        selectedMimeFilter,
        sortOrder
    ) { docs, query, category, mimeFilter, sort ->
        var list = docs

        // Search query filter
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(q) || (it.notes?.lowercase()?.contains(q) == true)
            }
        }

        // Category filter
        if (category != null) {
            list = list.filter { it.categoryId == category }
        }

        // MIME type filter
        if (mimeFilter != null) {
            list = when (mimeFilter) {
                "pdf" -> list.filter { it.mimeType.contains("pdf", ignoreCase = true) }
                "image" -> list.filter { it.mimeType.contains("image", ignoreCase = true) }
                else -> list
            }
        }

        // Sort order
        when (sort) {
            SortOrder.NEWEST_FIRST -> list.sortedByDescending { it.createdAt }
            SortOrder.OLDEST_FIRST -> list.sortedBy { it.createdAt }
            SortOrder.TITLE_A_Z -> list.sortedBy { it.title.lowercase() }
            SortOrder.EXPIRY_NEAREST -> list.sortedWith(
                compareBy(
                    { it.expiryDate == null }, // Put documents with expiry first
                    { it.expiryDate ?: Long.MAX_VALUE }
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Import UI State
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _importSuccess = MutableStateFlow(false)
    val importSuccess: StateFlow<Boolean> = _importSuccess.asStateFlow()

    fun clearImportStatus() {
        _importError.value = null
        _importSuccess.value = false
    }

    fun importDocument(
        uri: Uri,
        title: String,
        categoryId: String,
        expiryDate: Long?,
        notes: String?,
        reminderDaysList: List<Int>,
        onSuccess: (DocumentEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            _importSuccess.value = false

            val result = repository.importDocument(
                contentUri = uri,
                title = title,
                categoryId = categoryId,
                expiryDate = expiryDate,
                notes = notes,
                reminderDaysList = reminderDaysList
            )

            _isImporting.value = false
            result.onSuccess { doc ->
                _importSuccess.value = true
                onSuccess(doc)
            }.onFailure { error ->
                _importError.value = error.localizedMessage ?: "Failed to import and encrypt document."
            }
        }
    }

    fun deleteDocument(documentId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteDocument(documentId).onSuccess {
                onDeleted()
            }
        }
    }

    fun updateDocument(
        documentId: String,
        title: String,
        categoryId: String,
        expiryDate: Long?,
        notes: String?,
        reminderDaysList: List<Int>,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateDocument(
                documentId = documentId,
                title = title,
                categoryId = categoryId,
                expiryDate = expiryDate,
                notes = notes,
                reminderDaysList = reminderDaysList
            ).onSuccess {
                onSuccess()
            }
        }
    }

    fun deleteAllDocuments(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteAllDocuments().onSuccess {
                onComplete()
            }
        }
    }

    // Scanner & Cropper states
    val scannedImageUriForCrop = MutableStateFlow<Uri?>(null)
    val readyCroppedDocUri = MutableStateFlow<Uri?>(null)

    fun setScannedImageForCrop(uri: Uri?) {
        scannedImageUriForCrop.value = uri
    }

    fun setReadyCroppedDoc(uri: Uri?) {
        readyCroppedDocUri.value = uri
    }

    fun addCustomCategory(name: String, onAdded: (CategoryEntity) -> Unit = {}) {
        viewModelScope.launch {
            repository.addCustomCategory(name).onSuccess {
                onAdded(it)
            }
        }
    }
}
