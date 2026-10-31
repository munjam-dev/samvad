package com.samvad.core.permissions

/**
 * The one-time permissions screen shown after sign-in and profile setup. Microphone and camera are not here: they are
 * requested in context (first call, first voice note, first photo). Nothing in this flow blocks the app.
 */
enum class OnboardingStep { CONTACTS, NOTIFICATIONS, BATTERY }

/** What the person chose for a step, remembered so the screen is never shown for it again. */
enum class StepAnswer { ALLOWED, NOT_NOW }

/** What tapping "Allow" should do for a step. */
enum class AllowAction { REQUEST, OPEN_SETTINGS, NONE }

data class PermissionState(
  val sdk: Int,
  val granted: Set<OnboardingStep>,
  val answers: Map<OnboardingStep, StepAnswer>,
)

object PermissionOnboarding {
  const val NOTIFICATION_PERMISSION_SDK = 33
  const val BATTERY_OPTIMISATION_SDK = 23

  /** A step is relevant on this Android version. Notifications need a prompt only from Android 13. */
  fun applies(step: OnboardingStep, sdk: Int): Boolean = when (step) {
    OnboardingStep.CONTACTS -> true
    OnboardingStep.NOTIFICATIONS -> sdk >= NOTIFICATION_PERMISSION_SDK
    OnboardingStep.BATTERY -> sdk >= BATTERY_OPTIMISATION_SDK
  }

  /** The steps still to show, in order: contacts, notifications, then the optional battery step. */
  fun pending(state: PermissionState): List<OnboardingStep> =
    OnboardingStep.entries.filter { it in state.pendingSet() }

  private fun PermissionState.pendingSet(): Set<OnboardingStep> =
    OnboardingStep.entries.filter { applies(it, sdk) && it !in granted && it !in answers }.toSet()

  fun isDone(state: PermissionState): Boolean = pending(state).isEmpty()

  /**
   * "Allow" on a step. Android stops showing its dialog after a permission was refused and no rationale is offered
   * any more (permanent denial), so the person is sent to the system settings instead.
   */
  fun allowAction(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): AllowAction = when {
    granted -> AllowAction.NONE
    askedBefore && !shouldShowRationale -> AllowAction.OPEN_SETTINGS
    else -> AllowAction.REQUEST
  }

  /** The answer to remember once the system dialog (or settings round-trip) has finished. */
  fun answerAfter(granted: Boolean): StepAnswer = if (granted) StepAnswer.ALLOWED else StepAnswer.NOT_NOW

  /** Battery optimisation is only worth asking about when calls would otherwise ring late. */
  fun batteryStepUseful(sdk: Int, alreadyExempt: Boolean): Boolean = sdk >= BATTERY_OPTIMISATION_SDK && !alreadyExempt

  /** Steps fired automatically, back to back, after the intro. Battery is never auto-fired. */
  fun autoSteps(pending: List<OnboardingStep>): List<OnboardingStep> = pending.filter { it != OnboardingStep.BATTERY }

  /** Whether the system dialog can still appear: not if it was refused before and Android offers no rationale. */
  fun canRequest(askedBefore: Boolean, shouldShowRationale: Boolean): Boolean = !askedBefore || shouldShowRationale

  /** The optional battery card comes last and only when pending and useful. */
  fun showBatteryCard(pending: List<OnboardingStep>, sdk: Int, alreadyExempt: Boolean): Boolean =
    OnboardingStep.BATTERY in pending && batteryStepUseful(sdk, alreadyExempt)
}
