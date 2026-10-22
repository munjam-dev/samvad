package com.samvad.messenger.data.vault.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samvad.messenger.SamvadApplication
import java.util.concurrent.TimeUnit

/** Runs the automatic vault backup. All the work, and all the rules, are in [VaultBackupManager]. */
class VaultBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val manager = (applicationContext as? SamvadApplication)?.container?.vaultBackup ?: return Result.success()
    return when (manager.runScheduled(runAttemptCount)) {
      ScheduledOutcome.RETRY -> Result.retry()
      ScheduledOutcome.DONE -> Result.success()
    }
  }
}

/** Schedules one backup a day under the person's conditions (by default Wi-Fi and charging). */
object VaultBackupScheduler {
  private const val NAME = "vault_backup"

  fun apply(context: Context, status: VaultBackupStatus) {
    val work = WorkManager.getInstance(context.applicationContext)
    if (!status.enabled || !status.autoEnabled) {
      work.cancelUniqueWork(NAME)
      return
    }
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(if (status.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
      .setRequiresCharging(status.chargingOnly)
      .setRequiresBatteryNotLow(true)
      .setRequiresStorageNotLow(true)
      .build()
    val request = PeriodicWorkRequestBuilder<VaultBackupWorker>(1, TimeUnit.DAYS)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
      .build()
    work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
  }

  fun cancel(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
  }
}
