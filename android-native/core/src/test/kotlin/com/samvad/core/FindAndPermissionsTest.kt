package com.samvad.core

import com.samvad.core.permissions.AllowAction
import com.samvad.core.permissions.OnboardingStep
import com.samvad.core.permissions.PermissionOnboarding
import com.samvad.core.permissions.PermissionState
import com.samvad.core.permissions.StepAnswer
import com.samvad.core.phone.PersonQueries
import com.samvad.core.phone.PersonQuery
import com.samvad.core.phone.PhoneNumbers
import com.samvad.core.search.GroupCandidate
import com.samvad.core.search.PeopleSearch
import com.samvad.core.search.PersonCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindAndPermissionsTest {
  @Test fun `typed numbers default to +91`() {
    assertEquals("+919876543210", PhoneNumbers.normalizeLenient("98765 43210"))
    assertEquals("+919876543210", PhoneNumbers.normalizeLenient("09876543210"))
    assertEquals("+919876543210", PhoneNumbers.normalizeLenient("+91 98765-43210"))
    assertEquals("+919876543210", PhoneNumbers.normalizeLenient("919876543210"))
    assertEquals("+14155550123", PhoneNumbers.normalizeLenient("+1 415 555 0123"))
    assertEquals("+919876543210", PhoneNumbers.normalizeLenient("0091 9876543210"))
  }

  @Test fun `numbers libphonenumber refuses still normalise, like Firebase test numbers`() {
    assertNull(PhoneNumbers.normalize("+911234567890"))
    assertEquals("+911234567890", PhoneNumbers.normalizeLenient("+91 12345 67890"))
    assertEquals("+911234567890", PhoneNumbers.normalizeLenient("1234567890"))
  }

  @Test fun `rubbish is not a number`() {
    assertNull(PhoneNumbers.normalizeLenient("12345"))
    assertNull(PhoneNumbers.normalizeLenient(""))
    assertNull(PhoneNumbers.normalizeLenient("+"))
  }

  @Test fun `person query tells username from phone`() {
    assertEquals(PersonQuery.Phone("+919876543210"), PersonQueries.parse(" 98765 43210 "))
    assertEquals(PersonQuery.Username("asha_k"), PersonQueries.parse("@Asha_K"))
    assertEquals(PersonQuery.Username("asha"), PersonQueries.parse("asha"))
    assertEquals(PersonQuery.Invalid, PersonQueries.parse("@a"))
    assertEquals(PersonQuery.Invalid, PersonQueries.parse("  "))
    assertEquals(PersonQuery.Invalid, PersonQueries.parse("123"))
  }

  private val people = PeopleSearch.merge(
    contacts = mapOf("u1" to "Amma", "u2" to "Rahul Office"),
    chatPartners = mapOf("u1" to "Lakshmi S", "u3" to "Ravi Kumar"),
  )

  @Test fun `people are found by profile name and by saved contact name`() {
    assertEquals(listOf("u1"), PeopleSearch.people("lak", people).map { it.uid })
    assertEquals(listOf("u1"), PeopleSearch.people("amma", people).map { it.uid })
    assertEquals(listOf("u2"), PeopleSearch.people("office", people).map { it.uid })
  }

  @Test fun `ranking prefers prefixes, then chat partners`() {
    val list = listOf(
      PersonCandidate("a", "Dr Ravi", inChat = false),
      PersonCandidate("b", "Ravindra", inChat = false),
      PersonCandidate("c", "Gravity", inChat = true),
    )
    assertEquals(listOf("b", "a", "c"), PeopleSearch.people("rav", list).map { it.uid })
  }

  @Test fun `too short and unknown queries find nothing, and nobody outside the known set`() {
    assertTrue(PeopleSearch.people("r", people).isEmpty())
    assertTrue(PeopleSearch.people("zzz", people).isEmpty())
  }

  @Test fun `groups match by name`() {
    val g = listOf(GroupCandidate("g1", "Family"), GroupCandidate("g2", "Office team"))
    assertEquals(listOf("g2"), PeopleSearch.groups("team", g).map { it.conversationId })
  }

  @Test fun `permission steps are ordered and skip what does not apply`() {
    val all = PermissionState(34, emptySet(), emptyMap())
    assertEquals(listOf(OnboardingStep.CONTACTS, OnboardingStep.NOTIFICATIONS, OnboardingStep.BATTERY), PermissionOnboarding.pending(all))
    assertEquals(listOf(OnboardingStep.CONTACTS, OnboardingStep.BATTERY), PermissionOnboarding.pending(PermissionState(30, emptySet(), emptyMap())))
  }

  @Test fun `granted or answered steps are not shown again`() {
    val s = PermissionState(34, setOf(OnboardingStep.CONTACTS), mapOf(OnboardingStep.NOTIFICATIONS to StepAnswer.NOT_NOW))
    assertEquals(listOf(OnboardingStep.BATTERY), PermissionOnboarding.pending(s))
    val done = s.copy(answers = s.answers + (OnboardingStep.BATTERY to StepAnswer.NOT_NOW))
    assertTrue(PermissionOnboarding.isDone(done))
  }

  @Test fun `permanent denial opens settings instead of asking again`() {
    assertEquals(AllowAction.NONE, PermissionOnboarding.allowAction(true, true, false))
    assertEquals(AllowAction.REQUEST, PermissionOnboarding.allowAction(false, false, false))
    assertEquals(AllowAction.REQUEST, PermissionOnboarding.allowAction(false, true, true))
    assertEquals(AllowAction.OPEN_SETTINGS, PermissionOnboarding.allowAction(false, true, false))
  }

  @Test fun `answers are remembered from the result`() {
    assertEquals(StepAnswer.ALLOWED, PermissionOnboarding.answerAfter(true))
    assertEquals(StepAnswer.NOT_NOW, PermissionOnboarding.answerAfter(false))
    assertTrue(PermissionOnboarding.batteryStepUseful(34, false))
    assertTrue(!PermissionOnboarding.batteryStepUseful(34, true))
  }

  @Test fun autoFireSkipsBatteryAndPermanentDenials() {
    val pending = listOf(OnboardingStep.CONTACTS, OnboardingStep.NOTIFICATIONS, OnboardingStep.BATTERY)
    assertEquals(listOf(OnboardingStep.CONTACTS, OnboardingStep.NOTIFICATIONS), PermissionOnboarding.autoSteps(pending))
    assertTrue(PermissionOnboarding.canRequest(false, false))
    assertTrue(PermissionOnboarding.canRequest(true, true))
    assertTrue(!PermissionOnboarding.canRequest(true, false))
    assertTrue(PermissionOnboarding.showBatteryCard(pending, 34, false))
    assertTrue(!PermissionOnboarding.showBatteryCard(pending, 34, true))
    assertTrue(!PermissionOnboarding.showBatteryCard(listOf(OnboardingStep.CONTACTS), 34, false))
  }
}
