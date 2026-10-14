package com.samvad.core.design

/** Colours of a chat theme for one mode. ARGB longs, like the rest of the design system. */
data class ChatThemeColors(
  val background: Long,
  /** Colour (with its low alpha) of the India doodle drawn over [background]; null for none. */
  val pattern: Long?,
  val bubbleIncoming: Long,
  val bubbleOutgoing: Long,
  /** Message text on both bubble colours. */
  val text: Long,
  /** Time, ticks and "edited" on both bubble colours. */
  val meta: Long,
  val chip: Long,
  val chipText: Long,
)

/** A built-in chat theme (US-004). Names are string resources keyed by [id]. */
data class ChatTheme(val id: String, val light: ChatThemeColors, val dark: ChatThemeColors) {
  fun colors(dark: Boolean) = if (dark) this.dark else light
}

/** What sits behind a chat's messages: a built-in theme or the user's own photo. */
sealed interface ChatBackground {
  data class Theme(val id: String) : ChatBackground
  /** [slot] is the conversation ID, or [ChatThemes.DEFAULT_SLOT] for the photo used by all chats. */
  data class Photo(val slot: String) : ChatBackground
}

object ChatThemes {
  const val PHOTO = "photo"
  const val DEFAULT_SLOT = "default"
  const val MAX_DIM = 60
  const val DEFAULT_DIM_LIGHT = 20
  const val DEFAULT_DIM_DARK = 35

  // Dark variants share one structure: deep base, tonal bubbles, near-white text.
  private fun darkOf(bg: Long, incoming: Long, outgoing: Long, text: Long, meta: Long, doodle: Long = 0x1CFFFFFF) =
    ChatThemeColors(bg, doodle, incoming, outgoing, text, meta, incoming, text)

  val IVORY = ChatTheme(
    "ivory",
    light = ChatThemeColors(
      background = 0xFFF8F5EC, pattern = 0x2E167B52, bubbleIncoming = 0xFFFFFFFF, bubbleOutgoing = 0xFFD9E9D7,
      text = 0xFF1A221C, meta = 0xFF46564B, chip = 0xFFEDEAE0, chipText = 0xFF1A221C,
    ),
    dark = darkOf(0xFF101914, 0xFF1E2B22, 0xFF234A35, 0xFFF4F6F1, 0xFFB0BDB2),
  )

  val SAGE = ChatTheme(
    "sage",
    light = ChatThemeColors(
      background = 0xFFDCE8DA, pattern = 0x30105C3D, bubbleIncoming = 0xFFFFFFFF, bubbleOutgoing = 0xFFC6DEC5,
      text = 0xFF17201A, meta = 0xFF3F4F45, chip = 0xFFFFFFFF, chipText = 0xFF17201A,
    ),
    dark = darkOf(0xFF12201A, 0xFF1C2C23, 0xFF2A4D3A, 0xFFF2F6F2, 0xFFB4C6B8),
  )

  val MONSOON = ChatTheme(
    "monsoon",
    light = ChatThemeColors(
      background = 0xFFDDE5EA, pattern = 0x302D4A5C, bubbleIncoming = 0xFFFFFFFF, bubbleOutgoing = 0xFFCDE4D6,
      text = 0xFF172026, meta = 0xFF3F4D57, chip = 0xFFFFFFFF, chipText = 0xFF172026,
    ),
    dark = darkOf(0xFF10181D, 0xFF1B252C, 0xFF23413A, 0xFFEEF3F6, 0xFFB2C0C9),
  )

  val MARIGOLD = ChatTheme(
    "marigold",
    light = ChatThemeColors(
      background = 0xFFF6E9D6, pattern = 0x38A0561A, bubbleIncoming = 0xFFFFFDF8, bubbleOutgoing = 0xFFF3D9B8,
      text = 0xFF2A1E12, meta = 0xFF574330, chip = 0xFFFFF7EA, chipText = 0xFF2A1E12,
    ),
    dark = darkOf(0xFF1A140E, 0xFF2A2119, 0xFF4A3326, 0xFFF7EFE6, 0xFFD2BFA9),
  )

  /** Dark-first: the same deep forest look in both modes. */
  private val nightGardenColors = darkOf(0xFF0E1A14, 0xFF17251D, 0xFF234A35, 0xFFF1F5F0, 0xFFB5CBBB, doodle = 0x1F75D6A0)
  val NIGHT_GARDEN = ChatTheme("night_garden", light = nightGardenColors, dark = nightGardenColors)

  val all: List<ChatTheme> = listOf(IVORY, SAGE, MONSOON, MARIGOLD, NIGHT_GARDEN)
  val DEFAULT: ChatTheme = IVORY

  fun byId(id: String?): ChatTheme = all.firstOrNull { it.id == id } ?: DEFAULT

  /**
   * The background for [conversationId]: its own choice if set, else the default for all chats.
   * Unknown ids (from an older or newer app version) fall back to [DEFAULT].
   */
  fun resolve(defaultId: String?, overrides: Map<String, String>, conversationId: String): ChatBackground {
    val own = overrides[conversationId]
    return when {
      own == PHOTO -> ChatBackground.Photo(conversationId)
      own != null -> ChatBackground.Theme(byId(own).id)
      defaultId == PHOTO -> ChatBackground.Photo(DEFAULT_SLOT)
      else -> ChatBackground.Theme(byId(defaultId).id)
    }
  }

  fun clampDim(percent: Int): Int = percent.coerceIn(0, MAX_DIM)
}
