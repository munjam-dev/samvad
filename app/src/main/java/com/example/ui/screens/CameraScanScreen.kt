package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.theme.ElectricBlue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

@Composable
fun CameraScanScreen(
    onNavigateBack: () -> Unit,
    onPhotoCaptured: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Gallery Picker Fallback
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onPhotoCaptured(uri)
        }
    }

    // System Camera Fallback
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val systemCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            onPhotoCaptured(tempCameraUri!!)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        // Permission Request State
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Camera Permission",
                        tint = ElectricBlue,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Sanraksha uses your camera to scan physical documents, certificates, and ID cards directly into your encrypted vault.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    modifier = Modifier.testTag("btn_grant_camera_permission")
                ) {
                    Text("Grant Camera Permission", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = Color(0xFF1E293B)),
                    modifier = Modifier.testTag("btn_pick_from_gallery")
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Image from Gallery")
                }
            }
        }
    } else {
        // Active Camera Scanner Viewfinder
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Camera Preview Surface
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            imageCapture = capture

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                capture
                            )
                            camera = cam
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder Document Guide Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Document frame dimensions (approx. A4 ratio centered)
                val frameWidth = w * 0.85f
                val frameHeight = frameWidth * 1.414f.coerceAtMost(h * 0.65f / frameWidth * frameWidth)
                val left = (w - frameWidth) / 2f
                val top = (h - frameHeight) / 2f - 30.dp.toPx()

                // Dim surrounding area
                // Top
                drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset.Zero, size = Size(w, top))
                // Bottom
                drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, top + frameHeight), size = Size(w, h - (top + frameHeight)))
                // Left
                drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, top), size = Size(left, frameHeight))
                // Right
                drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(left + frameWidth, top), size = Size(w - (left + frameWidth), frameHeight))

                // Frame border
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(frameWidth, frameHeight),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Corner Reticles in Electric Blue
                val cornerLength = 28.dp.toPx()
                val cornerStroke = 4.dp.toPx()
                val reticleColor = Color(0xFF2563EB)

                // Top-Left
                drawLine(reticleColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth = cornerStroke)
                drawLine(reticleColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth = cornerStroke)

                // Top-Right
                drawLine(reticleColor, Offset(left + frameWidth, top), Offset(left + frameWidth - cornerLength, top), strokeWidth = cornerStroke)
                drawLine(reticleColor, Offset(left + frameWidth, top), Offset(left + frameWidth, top + cornerLength), strokeWidth = cornerStroke)

                // Bottom-Left
                drawLine(reticleColor, Offset(left, top + frameHeight), Offset(left + cornerLength, top + frameHeight), strokeWidth = cornerStroke)
                drawLine(reticleColor, Offset(left, top + frameHeight), Offset(left, top + frameHeight - cornerLength), strokeWidth = cornerStroke)

                // Bottom-Right
                drawLine(reticleColor, Offset(left + frameWidth, top + frameHeight), Offset(left + frameWidth - cornerLength, top + frameHeight), strokeWidth = cornerStroke)
                drawLine(reticleColor, Offset(left + frameWidth, top + frameHeight), Offset(left + frameWidth, top + frameHeight - cornerLength), strokeWidth = cornerStroke)
            }

            // Top Bar Controls: Back and Flash
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 40.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                // Flash toggle
                IconButton(
                    onClick = {
                        camera?.cameraControl?.enableTorch(!isFlashOn)
                        isFlashOn = !isFlashOn
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Torch",
                        tint = if (isFlashOn) Color.Yellow else Color.White
                    )
                }
            }

            // Instruction Label
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Align document within the frame",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                )
            }

            // Bottom Shutter Controls Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery button
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .testTag("scanner_btn_gallery")
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
                }

                // Shutter Capture Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = !isCapturing) {
                            val capture = imageCapture
                            if (capture != null) {
                                isCapturing = true
                                val cameraDir = File(context.cacheDir, "camera").apply {
                                    if (!exists()) mkdirs()
                                }
                                val photoFile = File(
                                    cameraDir,
                                    "DOC_SCAN_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
                                )
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                capture.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            isCapturing = false
                                            val savedUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                photoFile
                                            )
                                            onPhotoCaptured(savedUri)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            isCapturing = false
                                        }
                                    }
                                )
                            } else {
                                // Fallback to system camera launcher
                                val cameraDir = File(context.cacheDir, "camera").apply {
                                    if (!exists()) mkdirs()
                                }
                                val photoFile = File(cameraDir, "SYS_SCAN_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    photoFile
                                )
                                tempCameraUri = uri
                                systemCameraLauncher.launch(uri)
                            }
                        }
                        .testTag("scanner_btn_capture"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(36.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .border(3.dp, Color.Black, CircleShape)
                                .background(Color.White)
                        )
                    }
                }

                // System Camera fallback shortcut
                IconButton(
                    onClick = {
                        val cameraDir = File(context.cacheDir, "camera").apply {
                            if (!exists()) mkdirs()
                        }
                        val photoFile = File(cameraDir, "SYS_SCAN_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            photoFile
                        )
                        tempCameraUri = uri
                        systemCameraLauncher.launch(uri)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .testTag("scanner_btn_system_camera")
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "System Camera", tint = Color.White)
                }
            }
        }
    }
}
