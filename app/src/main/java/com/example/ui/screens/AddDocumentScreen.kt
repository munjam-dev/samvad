package com.example.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.DeepCobaltBlue
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.theme.SanrakshaSurfaceVariantLight
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDocumentScreen(
    viewModel: VaultViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCameraScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val importSuccess by viewModel.importSuccess.collectAsStateWithLifecycle()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var detectedFileName by remember { mutableStateOf("") }
    var detectedFileSize by remember { mutableStateOf(0L) }
    var detectedMimeType by remember { mutableStateOf("") }

    var title by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "identity") }
    var notes by remember { mutableStateOf("") }
    var expiryDateMillis by remember { mutableStateOf<Long?>(null) }

    val reminderDays = remember { mutableStateListOf(30, 7, 1) }

    var showDatePicker by remember { mutableStateOf(false) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    // Check if coming from the Camera Scanner & Cropper
    LaunchedEffect(Unit) {
        val cropped = viewModel.readyCroppedDocUri.value
        if (cropped != null) {
            selectedUri = cropped
            val timeStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())
            detectedFileName = "Scanned_Document_${System.currentTimeMillis()}.jpg"
            detectedMimeType = "image/jpeg"
            detectedFileSize = 250_000L
            if (title.isBlank()) {
                title = "Scanned Document - $timeStr"
            }
            viewModel.setReadyCroppedDoc(null)
        }
    }

    // System File Picker for PDF and Images
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            // Resolve file metadata
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        detectedFileName = cursor.getString(nameIndex) ?: "document"
                        if (title.isBlank()) {
                            // Strip extension for title suggestion
                            title = detectedFileName.substringBeforeLast(".")
                        }
                    }
                    if (sizeIndex != -1) {
                        detectedFileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
            detectedMimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add Document", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // File Selection Hero Area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                    }
                    .testTag("file_picker_dropzone"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (selectedUri != null) ElectricBlue else SanrakshaBorderLight)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (selectedUri != null) Color(0xFFDCFCE7) else SanrakshaSurfaceVariantLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selectedUri != null) Icons.Filled.CheckCircle else Icons.Filled.CloudUpload,
                            contentDescription = null,
                            tint = if (selectedUri != null) SuccessGreen else ElectricBlue,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedUri == null) {
                        Text(
                            text = "Add a Physical or Digital Document",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scan with Camera or pick existing PDFs & Images",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onNavigateToCameraScan,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_scan_camera")
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan Camera", fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_choose_document")
                            ) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Browse Files", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Text(
                            text = detectedFileName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${detectedMimeType} • ${formatBytes(detectedFileSize)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = ElectricBlue)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onNavigateToCameraScan,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rescan", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Change File", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Title Field
            Text(
                text = "Document Title *",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_doc_title"),
                placeholder = { Text("e.g. Passport, Vehicle Insurance, Degree") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = SanrakshaBorderLight
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Category Dropdown
            Text(
                text = "Category *",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            ExposedDropdownMenuBox(
                expanded = categoryDropdownExpanded,
                onExpandedChange = { categoryDropdownExpanded = !categoryDropdownExpanded }
            ) {
                val currentCategory = categories.find { it.id == selectedCategoryId }?.name ?: "Identity"
                OutlinedTextField(
                    value = currentCategory,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                        .testTag("dropdown_category"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = SanrakshaBorderLight
                    )
                )

                ExposedDropdownMenu(
                    expanded = categoryDropdownExpanded,
                    onDismissRequest = { categoryDropdownExpanded = false }
                ) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = {
                                selectedCategoryId = cat.id
                                categoryDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expiry Date (Optional)
            Text(
                text = "Document Expiry Date (Optional)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = if (expiryDateMillis != null) {
                    SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(expiryDateMillis!!))
                } else "",
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("No expiry set (Tap calendar to select)") },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "Select Date", tint = ElectricBlue)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
                    .testTag("input_expiry_date"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = SanrakshaBorderLight
                )
            )

            if (expiryDateMillis != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Expiry date configured",
                        style = MaterialTheme.typography.bodySmall.copy(color = SuccessGreen)
                    )
                    TextButton(onClick = { expiryDateMillis = null }) {
                        Text("Clear Date", color = ErrorRed, fontSize = 12.sp)
                    }
                }

                // Reminder intervals
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Notify me before expiry:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ReminderCheckbox(
                        label = "30 days",
                        checked = reminderDays.contains(30),
                        onCheckedChange = { checked ->
                            if (checked) reminderDays.add(30) else reminderDays.remove(30)
                        }
                    )
                    ReminderCheckbox(
                        label = "7 days",
                        checked = reminderDays.contains(7),
                        onCheckedChange = { checked ->
                            if (checked) reminderDays.add(7) else reminderDays.remove(7)
                        }
                    )
                    ReminderCheckbox(
                        label = "1 day",
                        checked = reminderDays.contains(1),
                        onCheckedChange = { checked ->
                            if (checked) reminderDays.add(1) else reminderDays.remove(1)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notes / Description
            Text(
                text = "Notes / Document ID (Optional)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_doc_notes"),
                placeholder = { Text("e.g. Policy #48291, Renewal required by end of month") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = SanrakshaBorderLight
                ),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error Display
            if (importError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = ErrorRed)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = importError ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = ErrorRed)
                        )
                    }
                }
            }

            // Submit Button
            Button(
                onClick = {
                    val uri = selectedUri
                    if (uri != null && title.isNotBlank()) {
                        viewModel.importDocument(
                            uri = uri,
                            title = title,
                            categoryId = selectedCategoryId,
                            expiryDate = expiryDateMillis,
                            notes = notes.ifBlank { null },
                            reminderDaysList = reminderDays.toList(),
                            onSuccess = {
                                onNavigateBack()
                            }
                        )
                    }
                },
                enabled = selectedUri != null && title.isNotBlank() && !isImporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_encrypt_and_save"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Encrypting & Saving to Vault...")
                } else {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Encrypt & Save to Vault", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // Material 3 Date Picker Dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = expiryDateMillis ?: (System.currentTimeMillis() + 86400000L * 30)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        expiryDateMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun ReminderCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = ElectricBlue)
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
