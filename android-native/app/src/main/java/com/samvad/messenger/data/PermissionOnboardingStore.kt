package com.samvad.messenger.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.samvad.core.permissions.OnboardingStep
import com.samvad.core.permissions.PermissionState
import com.samvad.core.permissions.StepAnswer

/** Remembers the answers to the first-run permissions screen, and reads the live system state for it. */
class PermissionOnboardingStore(private val context: Context) {
  private val prefs = context.getSharedPreferences("samvad_permission_onboarding", Context.MODE_PRIVATE)

  fun answer(step: OnboardingStep, answer: StepAnswer) {
    prefs.edit().putString("answer_${step.name}", answer.name).apply()
  }

  /** Whether the system dialog was already shown for this step (needed to tell "denied for good" from "never asked"). */
  fun markAsked(step: OnboardingStep) {
    prefs.edit().putBoolean("asked_${step.name}", true).apply()
  }

  fun wasAsked(step: OnboardingStep): Boolean = prefs.getBoolean("asked_${step.name}", false)

  fun isGranted(step: OnboardingStep): Boolean = when (step) {
    OnboardingStep.CONTACTS -> has(Manifest.permission.READ_CONTACTS)
    OnboardingStep.NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS)
    OnboardingStep.BATTERY ->
      Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
  }

  fun state(): PermissionState {
    val answers = OnboardingStep.entries.mapNotNull { step ->
      prefs.getString("answer_${step.name}", null)?.let { runCatching { StepAnswer.valueOf(it) }.getOrNull() }?.let { step to it }
    }.toMap()
    return PermissionState(Build.VERSION.SDK_INT, OnboardingStep.entries.filter(::isGranted).toSet(), answers)
  }

  private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
