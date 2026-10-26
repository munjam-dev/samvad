package com.samvad.core

import com.samvad.core.translate.TranslateFailure
import com.samvad.core.translate.TranslateLanguages
import com.samvad.core.translate.TranslateRules
import com.samvad.core.translate.TranslateState
import com.samvad.core.translate.TranslationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
  @Test fun listHasTheFourLanguages() {
    assertEquals(listOf("en", "hi", "te", "ta"), TranslateLanguages.all.map { it.tag })
  }

  @Test fun byTagAcceptsRegionTags() {
    assertEquals(TranslateLanguages.HINDI, TranslateLanguages.byTag("hi-IN"))
    assertEquals(TranslateLanguages.TAMIL, TranslateLanguages.byTag("TA_in"))
    assertNull(TranslateLanguages.byTag("fr"))
    assertNull(TranslateLanguages.byTag(null))
  }

  @Test fun defaultTargetFallsBackToEnglish() {
    assertEquals(TranslateLanguages.TELUGU, TranslateLanguages.defaultTarget("te"))
    assertEquals(TranslateLanguages.ENGLISH, TranslateLanguages.defaultTarget("de"))
  }

  @Test fun onlyPlainTextCanBeTranslated() {
    assertTrue(TranslateRules.canTranslate("text", "hello", deleted = false))
    assertFalse(TranslateRules.canTranslate("image", "hello", deleted = false))
    assertFalse(TranslateRules.canTranslate("text", "  ", deleted = false))
    assertFalse(TranslateRules.canTranslate("text", "hello", deleted = true))
    assertFalse(TranslateRules.canTranslate("text", "hello", deleted = false, pending = true))
    assertFalse(TranslateRules.canTranslate("text", "a".repeat(TranslateRules.MAX_CHARS + 1), deleted = false))
  }

  @Test fun toggleOnlyWorksWhenDone() {
    val working = TranslationEntry(TranslateLanguages.HINDI, TranslateState.Working)
    assertEquals(working, working.toggleOriginal())
    val done = TranslationEntry(TranslateLanguages.HINDI, TranslateState.Done("नमस्ते"))
    assertTrue(done.toggleOriginal().showOriginal)
    assertFalse(done.toggleOriginal().toggleOriginal().showOriginal)
  }

  @Test fun failureOrder() {
    assertEquals(TranslateFailure.UNKNOWN_LANGUAGE, TranslateRules.failureFor(true, false, false))
    assertEquals(TranslateFailure.SAME_LANGUAGE, TranslateRules.failureFor(true, true, true))
    assertEquals(TranslateFailure.OFFLINE, TranslateRules.failureFor(true, true, false))
    assertNull(TranslateRules.failureFor(false, true, false))
  }
}
