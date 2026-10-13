package com.samvad.core

import com.samvad.core.chat.ChatList
import com.samvad.core.chat.DisplayNames
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNamesTest {
  @Test fun `a saved contact shows under the saved name`() {
    assertEquals("Amma", DisplayNames.of("Lakshmi", savedName = "Amma", contactsAvailable = true))
  }

  @Test fun `someone not in my contacts shows their profile name with a tilde`() {
    assertEquals("~ Ravi", DisplayNames.of("Ravi", savedName = null, contactsAvailable = true))
    assertEquals("~ Ravi", DisplayNames.of("Ravi", savedName = "  ", contactsAvailable = true))
  }

  @Test fun `without contacts access names show plainly`() {
    assertEquals("Ravi", DisplayNames.of("Ravi", savedName = null, contactsAvailable = false))
  }

  @Test fun `a saved name wins even if the profile name is blank`() {
    assertEquals("Amma", DisplayNames.of("", savedName = "Amma", contactsAvailable = true))
  }

  @Test fun `a blank profile name never becomes a lone tilde`() {
    assertEquals("", DisplayNames.of(" ", savedName = null, contactsAvailable = true))
  }

  @Test fun `saved names are trimmed`() {
    assertEquals("Amma", DisplayNames.of("Lakshmi", savedName = " Amma ", contactsAvailable = true))
  }

  @Test fun `initials ignore the unsaved tilde`() {
    assertEquals("RK", ChatList.initials(DisplayNames.of("Ravi Kumar", savedName = null, contactsAvailable = true)))
  }
}
