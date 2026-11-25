package com.tpeditz.privatevault.v2.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tpeditz.privatevault.v2.PrivateVaultApplication
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import kotlinx.coroutines.flow.first
import java.io.FileOutputStream

/**
 * Background WorkManager worker for scheduled, opt-in Google Drive encrypted snapshots.
 *
 * Security & Recoverability Guarantees:
 * - Never invents transient/unrecoverable passwords.
 * - Never bypasses session locks or decrypts vault records while the vault is locked.
 * - Uploads only authorized pre-encrypted snapshots created during an unlocked session using the
 *   user's intentionally configured, Keystore-wrapped Cloud Recovery Password.
 * - If the vault has remained locked and no prepared snapshot is staged yet, sets a deferred status
 *   until the user unlocks the vault.
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
            CloudBackupScheduler.clearPendingSnapshot(applicationContext)
            return Result.success()
        }

        val expectedEmail = prefs.connectedGoogleAccount.first()
        val account = GoogleDriveAuthHelper.getLastSignedInAccount(applicationContext)
        if (account == null || expectedEmail.isBlank() || !account.email.equals(expectedEmail, ignoreCase = true)) {
            SafeVaultLogger.w("CloudBackupWorker", "Google account missing or changed; halting automatic backup.")
            CloudBackupScheduler.cancelAllCloudWork(applicationContext)
            CloudBackupScheduler.clearPendingSnapshot(applicationContext)
            prefs.setAutoCloudBackupStatus("Not connected — reconnect Google account")
            return Result.success()
        }

        if (!GoogleDriveAuthHelper.hasPermissions(applicationContext)) {
            prefs.setAutoCloudBackupStatus("Permission needed — please reconnect Google Drive")
            return Result.failure()
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

        val pendingFile = CloudBackupScheduler.pendingSnapshotFile(applicationContext)
        val pendingMeta = CloudBackupScheduler.readPendingSnapshotMeta(applicationContext)
        var hasValidPendingSnapshot = pendingFile.exists() &&
            pendingFile.length() > 0L &&
            pendingMeta != null &&
            pendingMeta.accountEmail.equals(account.email, ignoreCase = true)

        // If vault happens to be actively unlocked on Real Slot and backup is due (or no snapshot yet), prepare one cleanly
        if (!hasValidPendingSnapshot && app != null && (now - lastCloudMs >= intervalMs || lastCloudMs == 0L)) {
            val session = app.sessionManager.getActiveSession()
            val recoveryPwd = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(applicationContext)
            if (session != null &&
                session.slotId == com.tpeditz.privatevault.v2.security.VaultSessionManager.REAL_SLOT &&
                !session.isWiped &&
                recoveryPwd != null
            ) {
                val tempFile = java.io.File(pendingFile.parentFile, "auto_snapshot.tmp")
                try {
                    val items = app.repository.getAllItemsForActiveSlot()
                    FileOutputStream(tempFile).use { fos ->
                        app.repository.createAuthoritativeStreamingBackup(
                            items = items,
                            backupPasswordChars = recoveryPwd,
                            outputStream = fos
                        )
                        fos.flush()
                        fos.fd.sync()
                    }
                    if (pendingFile.exists()) pendingFile.delete()
                    tempFile.renameTo(pendingFile)
                    CloudBackupScheduler.writePendingSnapshotMeta(
                        applicationContext,
                        CloudBackupScheduler.PendingSnapshotMeta(
                            accountEmail = account.email.orEmpty(),
                            preparedAtEpochMs = now,
                            sizeBytes = pendingFile.length()
                        )
                    )
                    hasValidPendingSnapshot = true
                } catch (e: Exception) {
                    runCatching { tempFile.delete() }
                    SafeVaultLogger.e("CloudBackupWorker", "Failed preparing backup snapshot while unlocked", e)
                } finally {
                    java.util.Arrays.fill(recoveryPwd, '\u0000')
                }
            }
        }

        if (!hasValidPendingSnapshot) {
            if (now - lastCloudMs >= intervalMs || lastCloudMs == 0L) {
                val hasKey = AutoCloudBackupKeyStore.hasRecoveryPassword(applicationContext)
                if (!hasKey) {
                    prefs.setAutoCloudBackupStatus("Setup incomplete — set a backup password")
                } else {
                    prefs.setAutoCloudBackupStatus("Waiting for vault unlock to prepare backup")
                }
            }
            return Result.success()
        }

        val tokenResult = GoogleDriveAuthHelper.getAccessToken(applicationContext, account)
        var token = tokenResult.getOrNull()
        if (token.isNullOrBlank()) {
            prefs.setAutoCloudBackupStatus("Could not verify Google Drive authorization")
            return Result.retry()
        }

        val syncManager = app?.googleDriveSyncManager ?: GoogleDriveSyncManager()
        prefs.setAutoCloudBackupStatus("Uploading encrypted backup...")

        var syncResult = syncManager.uploadBackupSnapshotFile(token, pendingFile)
        if (syncResult is GoogleDriveSyncManager.SyncResult.Error && syncResult.httpCode == 401) {
            val refreshed = GoogleDriveAuthHelper.refreshAccessToken(applicationContext, account, token).getOrNull()
            if (!refreshed.isNullOrBlank()) {
                token = refreshed
                syncResult = syncManager.uploadBackupSnapshotFile(token, pendingFile)
            }
        }

        return when (syncResult) {
            is GoogleDriveSyncManager.SyncResult.Success -> {
                CloudBackupScheduler.clearPendingSnapshot(applicationContext)
                prefs.recordCloudBackup(System.currentTimeMillis())
                prefs.setAutoCloudBackupStatus("Automatic backup completed")
                SafeVaultLogger.i("CloudBackupWorker", "Scheduled automatic cloud backup uploaded successfully.")
                Result.success()
            }
            is GoogleDriveSyncManager.SyncResult.Error -> {
                if (syncResult.isAuthError) {
                    prefs.setAutoCloudBackupStatus("Google permission expired — please reconnect")
                    Result.failure()
                } else {
                    prefs.setAutoCloudBackupStatus("Upload paused — will retry on network connection")
                    Result.retry()
                }
            }
        }
    }
}
