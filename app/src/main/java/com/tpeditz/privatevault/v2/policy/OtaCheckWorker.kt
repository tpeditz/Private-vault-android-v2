package com.tpeditz.privatevault.v2.policy

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tpeditz.privatevault.v2.PrivateVaultApplication
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that syncs anonymous Supabase release policy & announcements
 * and runs a quiet eligibility check on user-opted OTA updates.
 *
 * Strictly zero vault DB access, zero telemetry uploads, zero silent APK installation.
 */
class OtaCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PrivateVaultApplication
            ?: return Result.success()

        return try {
            val autoEnabled = app.preferencesRepository.automaticOtaCheckEnabled.first()
            if (!autoEnabled) {
                return Result.success()
            }

            val syncResult = app.otaUpdateManager.syncPolicySnapshot(forceRefresh = true)
            val fresh = (syncResult as? PolicySyncResult.Fresh)?.snapshot
            val intervalHours = app.preferencesRepository.effectiveOtaCheckIntervalHours.first()
            val newInterval = fresh?.featureFlags?.updateCheckIntervalHours
                ?.let { AllowlistedFeatureFlags.clampIntervalHours(it) }
                ?: intervalHours
            if (newInterval != intervalHours) {
                app.preferencesRepository.setEffectiveOtaCheckIntervalHours(newInterval)
                OtaPolicyScheduler.schedulePeriodicPolicyCheck(applicationContext, newInterval)
            }

            if (fresh == null || fresh.featureFlags.onlineMaintenance) {
                return Result.success()
            }

            val otaResult = app.otaUpdateManager.checkForUpdates(isUserInitiated = false)
            if (otaResult is OtaState.UpdateAvailable || otaResult is OtaState.UpToDate) {
                app.preferencesRepository.setLastOtaCheckEpochMs(System.currentTimeMillis())
            }
            Result.success()
        } catch (e: Exception) {
            SafeVaultLogger.e("OtaCheckWorker", "Background policy sync skipped", e)
            Result.retry()
        }
    }
}

object OtaPolicyScheduler {
    const val UNIQUE_WORK_NAME = "private_vault_ota_policy_check"

    fun schedulePeriodicPolicyCheck(
        context: Context,
        intervalHours: Int = AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    ) {
        val clampedHours = AllowlistedFeatureFlags.clampIntervalHours(intervalHours).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<OtaCheckWorker>(
            clampedHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    fun cancelPeriodicPolicyCheck(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
