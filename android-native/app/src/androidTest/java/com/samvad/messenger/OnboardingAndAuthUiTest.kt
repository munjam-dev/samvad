package com.samvad.messenger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samvad.core.design.ThemeMode
import com.samvad.messenger.ui.onboarding.OnboardingScreen
import com.samvad.messenger.ui.theme.SamvadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingAndAuthUiTest {
  @get:Rule val compose = createComposeRule()

  @Test fun onboarding_pages_advance_and_finish() {
    var done = false
    compose.setContent { SamvadTheme(ThemeMode.LIGHT) { OnboardingScreen(onDone = { done = true }) } }
    compose.onNodeWithText("Your conversations, connected.").assertIsDisplayed()
    compose.onNodeWithText("Next").performClick()
    compose.onNodeWithText("Private by design").assertIsDisplayed()
    compose.onNodeWithText("Next").performClick()
    compose.onNodeWithText("Get started").performClick()
    assertTrue(done)
  }

  @Test fun skip_finishes_onboarding_in_dark_theme() {
    var done = false
    compose.setContent { SamvadTheme(ThemeMode.AMOLED) { OnboardingScreen(onDone = { done = true }) } }
    compose.onNodeWithText("Skip").performClick()
    assertTrue(done)
  }
}
