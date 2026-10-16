package com.samvad.core.profile

/** A language the app is fully translated into. [nativeName] is how speakers of it write its name. */
data class Language(val tag: String, val nativeName: String)

/**
 * The languages offered in the picker. A language is added only when every string is translated and the screens
 * have been checked in it; partly finished languages are not offered. Keep in step with res/xml/locales_config.xml.
 */
object Languages {
  val ENGLISH = Language("en", "English")
  val SUPPORTED = listOf(
    ENGLISH,
    Language("hi", "हिन्दी"),
    Language("te", "తెలుగు"),
    Language("ta", "தமிழ்"),
  )

  /** Scripts whose syllables break if letter spacing is applied; the UI uses the font's own spacing for them. */
  fun isIndic(tag: String?): Boolean = resolve(tag).tag != ENGLISH.tag

  /** The supported language for [tag] ("hi", "hi-IN", ...), or English when it is not one of ours. */
  fun resolve(tag: String?): Language {
    val base = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
    return SUPPORTED.firstOrNull { it.tag == base } ?: ENGLISH
  }
}
