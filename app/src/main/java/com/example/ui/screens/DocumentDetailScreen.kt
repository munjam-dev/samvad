package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.SanrakshaApplication
import com.example.data.model.CategoryEntity
import com.example.data.model.DocumentEntity
import com.example.ui.theme.DeepCobaltBlue
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.theme.SanrakshaSurfaceVariantLight
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    documentId: String,
    viewModel: VaultViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = (context.applicationContext as SanrakshaApplication).repository
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var document by remember { mutableStateOf<DocumentEntity?>(null) }
    var category by remember { mutableStateOf<CategoryEntity?>(null) }
    var isDecrypting by remember { mutableStateOf(true) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var currentPdfPage by remember { mutableIntStateOf(0) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var pdfRenderer: PdfRenderer? by remember { mutableStateOf(null) }
    var parcelFileDescriptor: ParcelFileDescriptor? by remember { mutableStateOf(null) }
    var tempPdfFile: File? by remember { mutableStateOf(null) }

    // Load Document and Decrypt Preview safely
    LaunchedEffect(documentId) {
        val doc = repository.getDocumentById(documentId)
        document = doc
        if (doc != null) {
            category = repository.getCategoryById(doc.categoryId)
            try {
                if (doc.mimeType.contains("pdf", ignoreCase = true)) {
                    withContext(Dispatchers.IO) {
                        val file = repository.getDecryptedCacheFile(doc)
                        tempPdfFile = file
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        parcelFileDescriptor = pfd
                        val renderer = PdfRenderer(pfd)
                        pdfRenderer = renderer
                        pdfPageCount = renderer.pageCount

                        if (renderer.pageCount > 0) {
                            val page = renderer.openPage(0)
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            previewBitmap = bitmap
                        }
                    }
                } else if (doc.mimeType.contains("image", ignoreCase = true)) {
                    withContext(Dispatchers.IO) {
                        val bytes = repository.getDecryptedBytes(doc)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        previewBitmap = bitmap
                    }
                }
            } catch (e: Exception) {
                previewError = "Preview unavailable: ${e.localizedMessage}"
            } finally {
                isDecrypting = false
            }
        } else {
            isDecrypting = false
            previewError = "Document not found."
        }
    }

    // Ephemeral cleanup on screen exit
    DisposableEffect(Unit) {
        onDispose {
            try {
                pdfRenderer?.close()
                parcelFileDescriptor?.close()
                tempPdfFile?.delete()
                (context.applicationContext as SanrakshaApplication).cryptoVaultManager.clearPreviewCache()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(document?.title ?: "Document", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            document?.let { doc ->
                                coroutineScope.launch {
                                    val uri = repository.prepareDocumentForExport(doc)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = doc.mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export ${doc.title}"))
                                }
                            }
                        },
                        modifier = Modifier.testTag("detail_share_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = ElectricBlue)
                    }

                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ErrorRed)
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
                .padding(16.dp)
        ) {
            val doc = document
            if (doc == null) {
                if (isDecrypting) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ElectricBlue)
                    }
                } else {
                    Text("Document not found.")
                }
            } else {
                // 1. In-App Secure Preview Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_preview_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDecrypting) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = ElectricBlue)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Decrypting securely in memory...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = "Preview of ${doc.title}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (doc.mimeType.contains("pdf")) Icons.Filled.PictureAsPdf else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = ElectricBlue,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = doc.originalFileName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = previewError ?: "Preview rendered safely",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }

                // If multi-page PDF, allow paging
                if (pdfPageCount > 1 && pdfRenderer != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (currentPdfPage > 0) {
                                    currentPdfPage--
                                    val renderer = pdfRenderer!!
                                    val page = renderer.openPage(currentPdfPage)
                                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    previewBitmap = bmp
                                }
                            },
                            enabled = currentPdfPage > 0
                        ) {
                            Text("Previous")
                        }
                        Text("Page ${currentPdfPage + 1} of $pdfPageCount", style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = {
                                if (currentPdfPage < pdfPageCount - 1) {
                                    currentPdfPage++
                                    val renderer = pdfRenderer!!
                                    val page = renderer.openPage(currentPdfPage)
                                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    previewBitmap = bmp
                                }
                            },
                            enabled = currentPdfPage < pdfPageCount - 1
                        ) {
                            Text("Next")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Encryption & Protection Security Badge
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SanrakshaSurfaceVariantLight),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AES-256-GCM Hardware Encrypted",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = SuccessGreen
                            )
                            Text(
                                text = "Protected under Android Keystore. Zero external leakage.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Document Metadata Breakdown
                Text("Metadata & Details", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MetadataRow("Category", category?.name ?: "General")
                        MetadataDivider()
                        MetadataRow("Original File", doc.originalFileName)
                        MetadataDivider()
                        MetadataRow("File Format", doc.mimeType)
                        MetadataDivider()
                        MetadataRow("Size", formatBytes(doc.fileSizeBytes))
                        MetadataDivider()
                        MetadataRow("Date Added", SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(doc.createdAt)))

                        if (doc.expiryDate != null) {
                            MetadataDivider()
                            val isExpired = doc.expiryDate < System.currentTimeMillis()
                            val expDateStr = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(doc.expiryDate))
                            MetadataRow(
                                "Expiry Date",
                                if (isExpired) "$expDateStr (Expired)" else expDateStr,
                                valueColor = if (isExpired) ErrorRed else WarningAmber
                            )
                        }

                        if (!doc.notes.isNullOrBlank()) {
                            MetadataDivider()
                            MetadataRow("Notes / ID", doc.notes)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 4. Primary Actions (Share & Delete)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val uri = repository.prepareDocumentForExport(doc)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = doc.mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export ${doc.title}"))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_export_document"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export / Share")
                    }

                    Button(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_delete_document"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEE2E2))
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete", color = ErrorRed, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showDeleteConfirm && document != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Document?") },
            text = { Text("Are you sure you want to delete \"${document!!.title}\"? This action cannot be undone and deletes the encrypted file permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDocument(document!!.id) {
                            showDeleteConfirm = false
                            onNavigateBack()
                        }
                    }
                ) {
                    Text("Delete Permanently", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MetadataRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = valueColor
        )
    }
}

@Composable
private fun MetadataDivider() {
    Spacer(modifier = Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SanrakshaBorderLight))
    Spacer(modifier = Modifier.height(8.dp))
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
