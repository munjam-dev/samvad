package com.samvad.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samvad.core.design.ChatBackground
import com.samvad.core.design.ChatThemes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.chatThemeStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_chat_themes")

/**
 * Chat theme choices (US-005 to US-007). [defaultId] applies to every chat without its own
 * [overrides] entry; [ChatThemes.PHOTO] means the user's own photo for that slot. [dims] and
 * [focus] are per photo slot (conversation ID or [ChatThemes.DEFAULT_SLOT]).
 */
data class ChatThemeSettings(
  val defaultId: String = ChatThemes.DEFAULT.id,
  val overrides: Map<String, String> = emptyMap(),
  val dims: Map<String, Int> = emptyMap(),
  /** Photo focus point, 0..1 on each axis; centre when absent. */
  val focus: Map<String, Pair<Float, Float>> = emptyMap(),
) {
  fun backgroundFor(conversationId: String): ChatBackground = ChatThemes.resolve(defaultId, overrides, conversationId)
}

/**
 * Stores chat theme choices and wallpaper photos on this device only. Photos are decoded upright,
 * downscaled to the screen and re-encoded as JPEG (which drops EXIF, including location), kept in
 * app-private storage, never uploaded, and deleted when replaced, cleared or on sign-out.
 */
class ChatThemeRepository(private val context: Context) {
  private val defaultKey = stringPreferencesKey("default_theme")
  private val overridesKey = stringPreferencesKey("overrides")
  private val dimsKey = stringPreferencesKey("dims")
  private val focusKey = stringPreferencesKey("focus")
  private val dir get() = File(context.filesDir, "wallpapers")

  val settings: Flow<ChatThemeSettings> = context.chatThemeStore.data.map { p ->
    ChatThemeSettings(
      defaultId = p[defaultKey] ?: ChatThemes.DEFAULT.id,
      overrides = decodeStrings(p[overridesKey]),
      dims = decodeStrings(p[dimsKey]).mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to ChatThemes.clampDim(it) } }.toMap(),
      focus = decodeStrings(p[focusKey]).mapNotNull { (k, v) ->
        val parts = v.split(',').mapNotNull(String::toFloatOrNull)
        if (parts.size == 2) k to (parts[0].coerceIn(0f, 1f) to parts[1].coerceIn(0f, 1f)) else null
      }.toMap(),
    )
  }

  fun photoFile(slot: String): File = File(dir, "${safe(slot)}.jpg")

  /** Sets the theme for every chat that has no choice of its own. */
  suspend fun setDefault(themeId: String) {
    context.chatThemeStore.edit { it[defaultKey] = themeId }
    if (themeId != ChatThemes.PHOTO) deletePhoto(ChatThemes.DEFAULT_SLOT)
  }

  /** Sets one chat's theme, or clears it ([themeId] null = "Use default"). */
  suspend fun setOverride(conversationId: String, themeId: String?) {
    context.chatThemeStore.edit { p ->
      val map = decodeStrings(p[overridesKey]).toMutableMap()
      if (themeId == null) map.remove(conversationId) else map[conversationId] = themeId
      p[overridesKey] = encode(map)
    }
    if (themeId != ChatThemes.PHOTO) deletePhoto(conversationId)
  }

  /**
   * Prepares [uri] as the wallpaper for [slot] and selects it. Returns false when the image can't be
   * read. The long edge is limited to [maxEdgePx] (the screen's long edge).
   */
  suspend fun usePhoto(slot: String, uri: Uri, maxEdgePx: Int): Boolean = withContext(Dispatchers.IO) {
    val bitmap = ImageCodec.decodeUpright(context.contentResolver, uri, maxEdgePx) ?: return@withContext false
    dir.mkdirs()
    val target = photoFile(slot)
    val temp = File(dir, "${safe(slot)}.tmp")
    temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    bitmap.recycle()
    if (!temp.renameTo(target)) {
      temp.copyTo(target, overwrite = true)
      temp.delete()
    }
    if (slot == ChatThemes.DEFAULT_SLOT) context.chatThemeStore.edit { it[defaultKey] = ChatThemes.PHOTO }
    else setOverride(slot, ChatThemes.PHOTO)
    true
  }

  suspend fun setDim(slot: String, percent: Int) = editMap(dimsKey) { it[slot] = ChatThemes.clampDim(percent).toString() }

  suspend fun setFocus(slot: String, x: Float, y: Float) =
    editMap(focusKey) { it[slot] = "${x.coerceIn(0f, 1f)},${y.coerceIn(0f, 1f)}" }

  /** A chat was left or deleted: forget its choice and photo. */
  suspend fun forget(conversationId: String) = setOverride(conversationId, null)

  /** Sign-out: remove every choice and photo from this device. */
  suspend fun clear() {
    context.chatThemeStore.edit { it.clear() }
    withContext(Dispatchers.IO) { dir.deleteRecursively() }
  }

  private suspend fun deletePhoto(slot: String) = withContext(Dispatchers.IO) {
    photoFile(slot).delete()
    Unit
  }

  private suspend fun editMap(key: Preferences.Key<String>, change: (MutableMap<String, String>) -> Unit) {
    context.chatThemeStore.edit { p ->
      val map = decodeStrings(p[key]).toMutableMap()
      change(map)
      p[key] = encode(map)
    }
  }

  private fun decodeStrings(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      val o = JSONObject(raw)
      o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
  }

  private fun encode(map: Map<String, String>) = JSONObject(map as Map<*, *>).toString()

  /** Conversation IDs are letters, digits and "_"; anything else is replaced so it can't escape [dir]. */
  private fun safe(slot: String) = slot.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
