package com.tpeditz.privatevault.v2.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tpeditz.privatevault.v2.PrivateVaultApplication
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.flow.first

/**
 * Background WorkManager worker for scheduled, opt-in Google Drive encrypted snapshots.
 *
 * Security & Coordination Guarantees:
 * - Never invents transient/unrecoverable passwords.
 * - Never bypasses session locks or decrypts vault records while the vault is locked.
 * - Uses immutable snapshot acquisition + SHA-256 verification before upload and only
 *   removes the specific snapshot ID that finished uploading cleanly.
 */
class CloudBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferencesRepository(applicationContext)
        val isEnabled = prefs.autoCloudBackupEnabled.first()
        if (!isEnabled) {
            CloudBackupScheduler.cancelAllCloudWork(applicationContext)
            CloudBackupScheduler.clearPendingSnapshots(applicationContext)
            return Result.success()
        }

        val expectedEmail = prefs.connectedGoogleAccount.first()
        if (expectedEmail.isBlank()) {
            SafeVaultLogger.w("CloudBackupWorker", "Google account not connected; halting automatic backup.")
            CloudBackupScheduler.cancelAllCloudWork(applicationContext)
            CloudBackupScheduler.clearPendingSnapshots(applicationContext)
            prefs.setAutoCloudBackupStatus("Not connected — connect Google account")
            return Result.success()
        }

        val lastCloudMs = prefs.lastCloudBackupEpochMs.first()
        val freq = prefs.cloudBackupFrequency.first()
        val intervalMs = when (freq) {
            CloudBackupFrequency.DAILY -> 24L * 60L * 60L * 1000L
            CloudBackupFrequency.WEEKLY -> 7L * 24L * 60L * 60L * 1000L
            CloudBackupFrequency.MONTHLY -> 30L * 24L * 60L * 60L * 1000L
        }
        val now = System.currentTimeMillis()

        val app = applicationContext as? PrivateVaultApplication
            ?: runCatching { PrivateVaultApplication.instance }.getOrNull()

        var stagedSnapshot = CloudBackupScheduler.acquireLatestReadySnapshot(applicationContext, expectedEmail)

        // If vault is actively unlocked on Real Slot and backup is due (or no snapshot ready yet), stage an immutable snapshot
        if (stagedSnapshot == null && app != null && (now - lastCloudMs >= intervalMs || lastCloudMs == 0L)) {
            val session = app.sessionManager.getActiveSession()
            val recoveryPwd = if (session?.slotId == VaultSessionManager.REAL_SLOT && !session.isWiped)
                AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(applicationContext) else null
            if (session != null &&
                session.slotId == VaultSessionManager.REAL_SLOT &&
                !session.isWiped &&
                recoveryPwd != null
            ) {
                try {
                    val items = app.repository.getAllItemsForActiveSlot(session.sessionId)
                    CloudBackupScheduler.stageEncryptedSnapshot(
                        context = applicationContext,
                        accountEmail = expectedEmail
                    ) { fos ->
                        app.repository.createAuthoritativeStreamingBackup(
                            items = items,
                            backupPasswordChars = recoveryPwd,
                            outputStream = fos,
                            expectedSessionId = session.sessionId
                        )
                    }
                    stagedSnapshot = CloudBackupScheduler.acquireLatestReadySnapshot(applicationContext, expectedEmail)
                } catch (e: Exception) {
                    SafeVaultLogger.e("CloudBackupWorker", "Failed staging automatic snapshot while unlocked", e)
                } finally {
                    java.util.Arrays.fill(recoveryPwd, '\u0000')
                }
            }
        }

        if (stagedSnapshot == null) {
            if (now - lastCloudMs >= intervalMs || lastCloudMs == 0L) {
                val hasKey = AutoCloudBackupKeyStore.hasRecoveryPassword(applicationContext)
                if (!hasKey) {
                    prefs.setAutoCloudBackupStatus("Setup incomplete — set an automatic backup password")
                } else {
                    prefs.setAutoCloudBackupStatus("Waiting for vault unlock to prepare backup")
                }
            }
            return Result.success()
        }

        val activeSnapshotId = stagedSnapshot.meta.snapshotId
        try {
            val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(applicationContext, expectedEmail)
            val token = when (authState) {
                is GoogleDriveAuthHelper.DriveAuthState.Authorized -> authState.accessToken
                is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                    prefs.setAutoCloudBackupStatus("Reconnect your Google account to continue")
                    return Result.failure()
                }
                is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                    prefs.setAutoCloudBackupStatus(authState.userMessage)
                    return if (authState.requiresReconnect) Result.failure() else Result.retry()
                }
            }

            val syncManager = app?.googleDriveSyncManager ?: GoogleDriveSyncManager()
            prefs.setAutoCloudBackupStatus("Uploading encrypted backup...")

            val syncResult = syncManager.uploadBackupSnapshotFile(token, stagedSnapshot.file)
            return when (syncResult) {
                is GoogleDriveSyncManager.SyncResult.Success -> {
                    CloudBackupScheduler.completeUploadedSnapshot(applicationContext, activeSnapshotId)
                    prefs.recordCloudBackup(System.currentTimeMillis())
                    prefs.setAutoCloudBackupStatus("Automatic backup completed")
                    SafeVaultLogger.i("CloudBackupWorker", "Scheduled cloud backup uploaded cleanly.")
                    Result.success()
                }
                is GoogleDriveSyncManager.SyncResult.Error -> {
                    if (syncResult.isAuthError) {
                        prefs.setAutoCloudBackupStatus("Reconnect your Google account to continue")
                        Result.failure()
                    } else {
                        prefs.setAutoCloudBackupStatus("Upload paused — will retry when connected")
                        Result.retry()
                    }
                }
            }
        } finally {
            CloudBackupScheduler.releaseUploadClaim(activeSnapshotId)
        }
    }
}
