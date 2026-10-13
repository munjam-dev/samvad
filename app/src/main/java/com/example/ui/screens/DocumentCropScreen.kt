package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.SanrakshaBorderLight
import com.example.util.DocumentFilter
import com.example.util.ImageCropperUtil
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentCropScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    onCropCompleted: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeFilter by remember { mutableStateOf(DocumentFilter.ORIGINAL) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    // Normalized crop box: 0.0f to 1.0f
    var cropLeft by remember { mutableFloatStateOf(0.06f) }
    var cropTop by remember { mutableFloatStateOf(0.06f) }
    var cropRight by remember { mutableFloatStateOf(0.94f) }
    var cropBottom by remember { mutableFloatStateOf(0.94f) }

    // Load initial bitmap from URI
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    originalBitmap = bmp
                    currentBitmap = bmp
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0F172A), // Dark studio canvas for contrast
        topBar = {
            TopAppBar(
                title = { Text("Crop & Adjust Document", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            currentBitmap?.let { bmp ->
                                val rotated = ImageCropperUtil.rotateBitmap(bmp, 90f)
                                currentBitmap = rotated
                                // Also update original
                                originalBitmap?.let { orig ->
                                    originalBitmap = ImageCropperUtil.rotateBitmap(orig, 90f)
                                }
                            }
                        },
                        modifier = Modifier.testTag("crop_rotate_button")
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90°", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Interactive Cropping Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = ElectricBlue)
                } else if (currentBitmap != null) {
                    val bmp = currentBitmap!!
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val containerWidth = maxWidth.value
                        val containerHeight = maxHeight.value

                        // Compute fitted image display dimensions
                        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                        val containerAspect = containerWidth / containerHeight

                        val (displayedWidth, displayedHeight) = if (bmpAspect > containerAspect) {
                            containerWidth to (containerWidth / bmpAspect)
                        } else {
                            (containerHeight * bmpAspect) to containerHeight
                        }

                        Box(
                            modifier = Modifier
                                .size(displayedWidth.dp, displayedHeight.dp)
                                .testTag("crop_preview_area")
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Document to crop",
                                modifier = Modifier.fillMaxSize()
                            )

                            // Interactive Crop Framing Overlay
                            InteractiveCropOverlay(
                                leftNorm = cropLeft,
                                topNorm = cropTop,
                                rightNorm = cropRight,
                                bottomNorm = cropBottom,
                                onBoundsChanged = { l, t, r, b ->
                                    cropLeft = l
                                    cropTop = t
                                    cropRight = r
                                    cropBottom = b
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Adjustments and Filter Chips
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Filter Mode Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Document Filter",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = activeFilter == DocumentFilter.ORIGINAL,
                                onClick = {
                                    activeFilter = DocumentFilter.ORIGINAL
                                    originalBitmap?.let { currentBitmap = it }
                                },
                                label = { Text("Original") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                            FilterChip(
                                selected = activeFilter == DocumentFilter.BLACK_AND_WHITE,
                                onClick = {
                                    activeFilter = DocumentFilter.BLACK_AND_WHITE
                                    originalBitmap?.let { currentBitmap = ImageCropperUtil.applyFilter(it, DocumentFilter.BLACK_AND_WHITE) }
                                },
                                label = { Text("Doc Clean") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                            FilterChip(
                                selected = activeFilter == DocumentFilter.GRAYSCALE,
                                onClick = {
                                    activeFilter = DocumentFilter.GRAYSCALE
                                    originalBitmap?.let { currentBitmap = ImageCropperUtil.applyFilter(it, DocumentFilter.GRAYSCALE) }
                                },
                                label = { Text("B&W") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Buttons: Reset & Confirm
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                cropLeft = 0f
                                cropTop = 0f
                                cropRight = 1f
                                cropBottom = 1f
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Frame")
                        }

                        Button(
                            onClick = {
                                if (currentBitmap != null && !isProcessing) {
                                    isProcessing = true
                                    coroutineScope.launch {
                                        val cropped = ImageCropperUtil.cropBitmap(
                                            source = currentBitmap!!,
                                            leftNorm = cropLeft,
                                            topNorm = cropTop,
                                            rightNorm = cropRight,
                                            bottomNorm = cropBottom
                                        )
                                        val filtered = ImageCropperUtil.applyFilter(cropped, activeFilter)
                                        val savedUri = ImageCropperUtil.saveBitmapToTempUri(context, filtered)
                                        withContext(Dispatchers.Main) {
                                            isProcessing = false
                                            onCropCompleted(savedUri)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp)
                                .testTag("btn_confirm_crop"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                            enabled = !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Scanned Doc", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Interactive draggable overlay that draws the translucent backdrop,
 * rule-of-thirds grid, and 4 corner drag handles.
 */
@Composable
private fun InteractiveCropOverlay(
    leftNorm: Float,
    topNorm: Float,
    rightNorm: Float,
    bottomNorm: Float,
    onBoundsChanged: (Float, Float, Float, Float) -> Unit
) {
    var activeHandle by remember { mutableStateOf<DragHandle?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width
                        val h = size.height

                        val cropRect = Rect(
                            left = leftNorm * w,
                            top = topNorm * h,
                            right = rightNorm * w,
                            bottom = bottomNorm * h
                        )

                        val threshold = 55f
                        val touch = offset

                        activeHandle = when {
                            (Offset(cropRect.left, cropRect.top) - touch).getDistance() <= threshold -> DragHandle.TOP_LEFT
                            (Offset(cropRect.right, cropRect.top) - touch).getDistance() <= threshold -> DragHandle.TOP_RIGHT
                            (Offset(cropRect.left, cropRect.bottom) - touch).getDistance() <= threshold -> DragHandle.BOTTOM_LEFT
                            (Offset(cropRect.right, cropRect.bottom) - touch).getDistance() <= threshold -> DragHandle.BOTTOM_RIGHT
                            cropRect.contains(touch) -> DragHandle.BODY
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val w = size.width
                        val h = size.height
                        val dxNorm = dragAmount.x / w
                        val dyNorm = dragAmount.y / h

                        when (activeHandle) {
                            DragHandle.TOP_LEFT -> {
                                val newLeft = (leftNorm + dxNorm).coerceIn(0f, rightNorm - 0.15f)
                                val newTop = (topNorm + dyNorm).coerceIn(0f, bottomNorm - 0.15f)
                                onBoundsChanged(newLeft, newTop, rightNorm, bottomNorm)
                            }
                            DragHandle.TOP_RIGHT -> {
                                val newRight = (rightNorm + dxNorm).coerceIn(leftNorm + 0.15f, 1f)
                                val newTop = (topNorm + dyNorm).coerceIn(0f, bottomNorm - 0.15f)
                                onBoundsChanged(leftNorm, newTop, newRight, bottomNorm)
                            }
                            DragHandle.BOTTOM_LEFT -> {
                                val newLeft = (leftNorm + dxNorm).coerceIn(0f, rightNorm - 0.15f)
                                val newBottom = (bottomNorm + dyNorm).coerceIn(topNorm + 0.15f, 1f)
                                onBoundsChanged(newLeft, topNorm, rightNorm, newBottom)
                            }
                            DragHandle.BOTTOM_RIGHT -> {
                                val newRight = (rightNorm + dxNorm).coerceIn(leftNorm + 0.15f, 1f)
                                val newBottom = (bottomNorm + dyNorm).coerceIn(topNorm + 0.15f, 1f)
                                onBoundsChanged(leftNorm, topNorm, newRight, newBottom)
                            }
                            DragHandle.BODY -> {
                                val width = rightNorm - leftNorm
                                val height = bottomNorm - topNorm
                                val newLeft = (leftNorm + dxNorm).coerceIn(0f, 1f - width)
                                val newTop = (topNorm + dyNorm).coerceIn(0f, 1f - height)
                                onBoundsChanged(newLeft, newTop, newLeft + width, newTop + height)
                            }
                            null -> {}
                        }
                    },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null }
                )
            }
    ) {
        val w = size.width
        val h = size.height

        val leftPx = leftNorm * w
        val topPx = topNorm * h
        val rightPx = rightNorm * w
        val bottomPx = bottomNorm * h

        val cropWidth = rightPx - leftPx
        val cropHeight = bottomPx - topPx

        // 1. Dim outside region
        // Top
        drawRect(color = Color.Black.copy(alpha = 0.55f), topLeft = Offset.Zero, size = Size(w, topPx))
        // Bottom
        drawRect(color = Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, bottomPx), size = Size(w, h - bottomPx))
        // Left
        drawRect(color = Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, topPx), size = Size(leftPx, cropHeight))
        // Right
        drawRect(color = Color.Black.copy(alpha = 0.55f), topLeft = Offset(rightPx, topPx), size = Size(w - rightPx, cropHeight))

        // 2. Crop border outline
        drawRect(
            color = Color.White,
            topLeft = Offset(leftPx, topPx),
            size = Size(cropWidth, cropHeight),
            style = Stroke(width = 2.dp.toPx())
        )

        // 3. Rule of thirds guidelines inside frame
        val stepX = cropWidth / 3f
        val stepY = cropHeight / 3f
        drawLine(color = Color.White.copy(alpha = 0.35f), start = Offset(leftPx + stepX, topPx), end = Offset(leftPx + stepX, bottomPx), strokeWidth = 1.dp.toPx())
        drawLine(color = Color.White.copy(alpha = 0.35f), start = Offset(leftPx + stepX * 2, topPx), end = Offset(leftPx + stepX * 2, bottomPx), strokeWidth = 1.dp.toPx())
        drawLine(color = Color.White.copy(alpha = 0.35f), start = Offset(leftPx, topPx + stepY), end = Offset(rightPx, topPx + stepY), strokeWidth = 1.dp.toPx())
        drawLine(color = Color.White.copy(alpha = 0.35f), start = Offset(leftPx, topPx + stepY * 2), end = Offset(rightPx, topPx + stepY * 2), strokeWidth = 1.dp.toPx())

        // 4. Prominent corner drag handles (Electric Blue circles with white borders)
        val handleRadius = 12.dp.toPx()
        val corners = listOf(
            Offset(leftPx, topPx),
            Offset(rightPx, topPx),
            Offset(leftPx, bottomPx),
            Offset(rightPx, bottomPx)
        )
        corners.forEach { corner ->
            drawCircle(color = ElectricBlue, radius = handleRadius, center = corner)
            drawCircle(color = Color.White, radius = handleRadius, center = corner, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

private enum class DragHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    BODY
}
