package com.samvad.core.translate

/** A language a message can be translated into. [nativeName] is shown as is, in its own script. */
data class TranslateLanguage(val tag: String, val nativeName: String)

object TranslateLanguages {
  val ENGLISH = TranslateLanguage("en", "English")
  val HINDI = TranslateLanguage("hi", "हिन्दी")
  val TELUGU = TranslateLanguage("te", "తెలుగు")
  val TAMIL = TranslateLanguage("ta", "தமிழ்")

  val all: List<TranslateLanguage> = listOf(ENGLISH, HINDI, TELUGU, TAMIL)

  fun byTag(tag: String?): TranslateLanguage? {
    val base = tag?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
    return all.firstOrNull { it.tag == base }
  }

  /** The device language when it is on the list, otherwise English. */
  fun defaultTarget(deviceTag: String?): TranslateLanguage = byTag(deviceTag) ?: ENGLISH
}

/** Why a translation could not be shown. Mapped to plain-words copy in the app. */
enum class TranslateFailure { OFFLINE, UNKNOWN_LANGUAGE, SAME_LANGUAGE, FAILED }

sealed interface TranslateState {
  /** Fetching the on-device language pack (first use of a language). */
  data object Downloading : TranslateState
  data object Working : TranslateState
  data class Done(val text: String) : TranslateState
  data class Failed(val reason: TranslateFailure) : TranslateState
}

/** A translation shown under one message. Lives in memory only: never stored, never sent. */
data class TranslationEntry(
  val target: TranslateLanguage,
  val state: TranslateState,
  val showOriginal: Boolean = false,
) {
  fun toggleOriginal(): TranslationEntry = if (state is TranslateState.Done) copy(showOriginal = !showOriginal) else this
}

object TranslateRules {
  const val MAX_CHARS = 5000

  /** Only plain text messages. Media, deleted, pending and locked messages are not translated. */
  fun canTranslate(kind: String, text: String, deleted: Boolean, pending: Boolean = false): Boolean =
    kind == "text" && !deleted && !pending && text.isNotBlank() && text.length <= MAX_CHARS

  fun failureFor(offline: Boolean, languageDetected: Boolean, sameLanguage: Boolean): TranslateFailure? = when {
    !languageDetected -> TranslateFailure.UNKNOWN_LANGUAGE
    sameLanguage -> TranslateFailure.SAME_LANGUAGE
    offline -> TranslateFailure.OFFLINE
    else -> null
  }
}
