package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DocumentFilter {
    ORIGINAL,
    BLACK_AND_WHITE,
    GRAYSCALE
}

object ImageCropperUtil {

    /**
     * Crops the source bitmap using normalized coordinates (0.0f to 1.0f).
     */
    fun cropBitmap(
        source: Bitmap,
        leftNorm: Float,
        topNorm: Float,
        rightNorm: Float,
        bottomNorm: Float
    ): Bitmap {
        val width = source.width
        val height = source.height

        val x = (leftNorm.coerceIn(0f, 1f) * width).toInt().coerceAtLeast(0)
        val y = (topNorm.coerceIn(0f, 1f) * height).toInt().coerceAtLeast(0)
        val targetWidth = ((rightNorm.coerceIn(0f, 1f) * width) - x).toInt().coerceAtLeast(1)
        val targetHeight = ((bottomNorm.coerceIn(0f, 1f) * height) - y).toInt().coerceAtLeast(1)

        val safeWidth = targetWidth.coerceAtMost(width - x)
        val safeHeight = targetHeight.coerceAtMost(height - y)

        return Bitmap.createBitmap(source, x, y, safeWidth, safeHeight)
    }

    /**
     * Rotates bitmap by given degrees.
     */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Applies document enhancement filter (e.g., high contrast B&W or Grayscale).
     */
    fun applyFilter(source: Bitmap, filter: DocumentFilter): Bitmap {
        return when (filter) {
            DocumentFilter.ORIGINAL -> source
            DocumentFilter.GRAYSCALE -> {
                val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint()
                val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
                output
            }
            DocumentFilter.BLACK_AND_WHITE -> {
                // High contrast document mode for sharp text & clean paper background
                val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint()

                // Grayscale + Contrast stretch
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.5f, 1.5f, 1.5f, 0f, -120f,
                        1.5f, 1.5f, 1.5f, 0f, -120f,
                        1.5f, 1.5f, 1.5f, 0f, -120f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
                output
            }
        }
    }

    /**
     * Saves processed bitmap to a temporary JPEG cache file and returns its FileProvider content URI.
     */
    fun saveBitmapToTempUri(context: Context, bitmap: Bitmap): Uri {
        val cameraDir = File(context.cacheDir, "camera").apply {
            if (!exists()) mkdirs()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(cameraDir, "SCAN_${timeStamp}.jpg")

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.flush()
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
