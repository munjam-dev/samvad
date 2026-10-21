package com.samvad.core

import com.samvad.core.search.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {
  @Test
  fun `splits on spaces and punctuation, folds case`() {
    val t = Tokenizer.tokens("Hello, world! Café-bar")
    assertEquals(listOf("hello", "world", "cafe", "bar"), t.map { it.norm })
  }

  @Test
  fun `positions point back into the original text`() {
    val text = "see you at noon"
    val t = Tokenizer.tokens(text)
    for (tok in t) assertEquals(tok.norm, text.substring(tok.start, tok.end).lowercase())
  }

  @Test
  fun `keeps Devanagari combining marks inside the word`() {
    val t = Tokenizer.tokens("नमस्ते दुनिया")
    assertEquals(listOf("नमस्ते", "दुनिया"), t.map { it.norm })
  }

  @Test
  fun `keeps Telugu and Tamil words whole`() {
    assertEquals(listOf("నమస్కారం"), Tokenizer.tokens("నమస్కారం!").map { it.norm })
    assertEquals(listOf("வணக்கம்"), Tokenizer.tokens("வணக்கம்.").map { it.norm })
  }

  @Test
  fun `zero width joiners do not split a word but are not part of it`() {
    val withZwnj = "क्‌ष"
    val t = Tokenizer.tokens(withZwnj)
    assertEquals(1, t.size)
    assertEquals("क्ष", t[0].norm)
  }

  @Test
  fun `numbers and emoji are handled without crashing`() {
    assertEquals(listOf("call", "9876543210"), Tokenizer.tokens("call 9876543210 😀").map { it.norm })
    assertEquals(emptyList<String>(), Tokenizer.tokens("😀 🎉 !!!").map { it.norm })
  }

  @Test
  fun `terms removes duplicates and indexForm joins with single spaces`() {
    assertEquals(listOf("hi", "there"), Tokenizer.terms("hi there hi"))
    assertEquals("hi there hi", Tokenizer.indexForm("hi  there\nhi"))
  }

  @Test
  fun `folding never changes string length`() {
    for (s in listOf("Café", "HELLO", "नमस्ते", "123")) assertEquals(s.length, Tokenizer.fold(s).length)
  }
}
