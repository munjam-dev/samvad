package com.samvad.core.chat

/**
 * How a person is named on this device. People saved in my phone's contacts appear under that
 * name; others under their profile name with a "~ " prefix, so an unknown sender is never mistaken
 * for a contact. Phone numbers of people not in my contacts are never shown. Without contacts
 * access there is nothing to compare against, so profile names show plainly.
 */
object DisplayNames {
  const val UNSAVED_PREFIX = "~ "

  fun of(profileName: String, savedName: String?, contactsAvailable: Boolean): String {
    savedName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val name = profileName.trim()
    return if (contactsAvailable && name.isNotEmpty()) UNSAVED_PREFIX + name else name
  }
}
