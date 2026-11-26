package com.tpeditz.privatevault.v2.ui

import com.tpeditz.privatevault.v2.data.AuthenticatorRecord
import com.tpeditz.privatevault.v2.data.BackupPayload
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.data.DocumentRecord
import com.tpeditz.privatevault.v2.data.MediaRecord
import com.tpeditz.privatevault.v2.data.NoteRecord
import com.tpeditz.privatevault.v2.data.SecureLinkRecord
import com.tpeditz.privatevault.v2.data.ThemePreference
import com.tpeditz.privatevault.v2.data.VaultAlbumRecord
import com.tpeditz.privatevault.v2.data.VaultFolder
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator

data class PrivacyHealthMetrics(
    val totalItemsCount: Int = 0,
    val averagePasswordScore: Int = 0,
    val weakPasswordsCount: Int = 0,
    val reusedPasswordsCount: Int = 0,
    val lastLocalBackupEpochMs: Long = 0L,
    val lastCloudBackupEpochMs: Long = 0L,
    val isAutoBackupEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val isScreenshotProtected: Boolean = true,
    val autoLockTimeoutSeconds: Int = 60,
    val isUpdateAvailable: Boolean = false
)

data class VaultUiState(
    val isVaultInitialized: Boolean = false,
    val isUnlocked: Boolean = false,
    val activeSlotId: Int? = null,
    val isDecoyPolicyConfigured: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val currentTab: PrimaryTab = PrimaryTab.HOME,
    val currentSecondary: SecondaryDestination = SecondaryDestination.None,
    val corruptedRecordsCount: Int = 0,

    // Collections for currently unlocked active slot
    val notes: List<NoteRecord> = emptyList(),
    val credentials: List<CredentialRecord> = emptyList(),
    val media: List<MediaRecord> = emptyList(),
    val documents: List<DocumentRecord> = emptyList(),
    val secureLinks: List<SecureLinkRecord> = emptyList(),
    val folders: List<VaultFolder> = emptyList(),
    val albums: List<VaultAlbumRecord> = emptyList(),
    val authenticators: List<AuthenticatorRecord> = emptyList(),

    // Search and Scoped Filters
    val searchQuery: String = "",
    val selectedFolderId: String? = null,
    val selectedCategory: String? = null,
    val filterFavoritesOnly: Boolean = false,
    val filterArchivedOnly: Boolean = false,
    val filterTrashOnly: Boolean = false,
    val filterWeakPasswordsOnly: Boolean = false,
    val filterReusedPasswordsOnly: Boolean = false,

    // Tools & TOTP
    val totpRemainingSeconds: Int = 30,
    val totpEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    val clipboardCountdownSeconds: Int? = null,

    // Privacy Dashboard Metrics
    val privacyMetrics: PrivacyHealthMetrics = PrivacyHealthMetrics(),

    // Security and Appearance Preferences
    val screenshotProtectionEnabled: Boolean = true,
    val lockOnScreenOff: Boolean = true,
    val autoLockTimeoutSeconds: Int = 60,
    val clipboardTtlSeconds: Int = 30,
    val isBiometricsConfigured: Boolean = false,

    // Cloud Backup & Scheduling State
    val connectedGoogleEmail: String = "",
    val autoCloudBackupEnabled: Boolean = false,
    val cloudBackupFrequency: CloudBackupFrequency = CloudBackupFrequency.WEEKLY,
    val cloudBackupWifiOnly: Boolean = true,
    val autoCloudBackupKeyConfigured: Boolean = false,
    val isCloudRecoveryPasswordLegacyShort: Boolean = false,
    val lastCloudBackupStatusMessage: String? = null,
    val lastCloudBackupEpochMs: Long = 0L,

    // OTA State
    val automaticOtaCheckEnabled: Boolean = true,
    val lastOtaCheckEpochMs: Long = 0L,

    // Incoming Share-to-Vault State
    val pendingSharedText: String? = null,

    // Dialogs and feedback
    val activeSnackbarMessage: String? = null,
    val activeErrorMessage: String? = null,
    val pendingRestorePayload: BackupPayload? = null,
    val pendingRestoreError: VaultErrorMapper.FriendlyRestoreError? = null,
    val isLoading: Boolean = false,
    val backupProgressRatio: Float? = null,
    val backupStatusMessage: String? = null
)
