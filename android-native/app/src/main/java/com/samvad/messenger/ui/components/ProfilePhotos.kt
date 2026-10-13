package com.samvad.messenger.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.samvad.core.chat.Profile
import kotlinx.coroutines.flow.StateFlow

/**
 * Supplies profile photos to [Avatar] (Phase 1.6): the photo path for a user ID, from the profiles
 * already loaded for chats, and a decoder with a small in-memory cache so lists scroll smoothly.
 * Photos are read through the Storage SDK, so the rules (signed in, not blocked) apply.
 */
class PhotoSource(
  private val profiles: StateFlow<Map<String, Profile>>,
  private val decode: suspend (path: String, maxEdgePx: Int) -> Bitmap?,
) {
  private val memory = LruCache<String, ImageBitmap>(64)

  @Composable
  fun pathFor(uid: String): String? = profiles.collectAsState().value[uid]?.photoPath

  suspend fun load(path: String, px: Int): ImageBitmap? {
    val key = "$path@$px"
    memory.get(key)?.let { return it }
    return decode(path, px)?.asImageBitmap()?.also { memory.put(key, it) }
  }
}

val LocalPhotoSource = staticCompositionLocalOf<PhotoSource?> { null }
