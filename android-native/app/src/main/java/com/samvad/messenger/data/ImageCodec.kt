package com.samvad.messenger.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.samvad.core.media.ImageQuality
import com.samvad.core.media.MediaRules

/**
 * Decodes picked images upright. Cameras often store portrait photos sideways with an EXIF
 * orientation tag; BitmapFactory ignores it, so without this photos would be sent rotated. The
 * callers re-encode to JPEG, which drops the EXIF data (including location) afterwards.
 */
internal object ImageCodec {
  /** At most about [maxEdgePx] on the long edge (power-of-two sampling), rotated as the tag says. */
  fun decodeUpright(resolver: ContentResolver, uri: Uri, maxEdgePx: Int): Bitmap? =
    decode(resolver, uri) { w, h -> MediaRules.sampleSize(w, h, maxEdgePx) }

  /** Full size unless that would be over [maxPixels]; then a power-of-two fraction of it. For original quality. */
  fun decodeUprightWithin(resolver: ContentResolver, uri: Uri, maxPixels: Long): Bitmap? =
    decode(resolver, uri) { w, h -> ImageQuality.decodeSampleSize(w, h, maxPixels) }

  private fun decode(resolver: ContentResolver, uri: Uri, sampleFor: (Int, Int) -> Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val orientation = runCatching {
      resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = orientationMatrix(orientation) ?: return decoded
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright !== decoded) decoded.recycle()
    return upright
  }

  private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
    else -> null
  }
}
