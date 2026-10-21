package com.samvad.messenger

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samvad.core.design.ThemeMode
import com.samvad.core.profile.Languages
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.language.LanguageList
import com.samvad.messenger.ui.language.LanguageScreen
import com.samvad.messenger.ui.theme.SamvadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke tests for screens that need no Firebase, so they run on any emulator or device. */
@RunWith(AndroidJUnit4::class)
class NoBackendScreensUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

  @Test fun language_list_names_each_language_in_its_own_script_and_reports_the_choice() {
    var chosen: String? = null
    compose.setContent {
      SamvadTheme(ThemeMode.LIGHT) { LanguageList(selected = "en", onSelect = { chosen = it }) }
    }
    Languages.SUPPORTED.forEach { compose.onNodeWithText(it.nativeName).assertIsDisplayed() }
    compose.onNodeWithText("English").assertIsSelected()
    compose.onNodeWithText("हिन्दी").performClick()
    assertEquals("hi", chosen)
  }

  @Test fun language_screen_shows_title_and_continue_button() {
    var continued = false
    compose.setContent { SamvadTheme(ThemeMode.LIGHT) { LanguageScreen(onContinue = { continued = true }) } }
    compose.onNodeWithText(string(R.string.language_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.language_continue)).performClick()
    assertTrue(continued)
  }

  @Test fun empty_state_shows_its_copy_and_runs_the_action() {
    var acted = false
    compose.setContent {
      SamvadTheme(ThemeMode.AMOLED) {
        EmptyState(
          icon = Icons.Outlined.Call,
          title = string(R.string.calls_empty_title),
          body = string(R.string.calls_empty_body),
          action = "Start",
          onAction = { acted = true },
        )
      }
    }
    compose.onNodeWithText(string(R.string.calls_empty_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.calls_empty_body)).assertIsDisplayed()
    compose.onNodeWithText("Start").performClick()
    assertTrue(acted)
  }
}
