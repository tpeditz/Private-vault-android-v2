package com.tpeditz.privatevault.v2.ui

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import com.tpeditz.privatevault.v2.BuildConfig
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.data.AuthResult
import com.tpeditz.privatevault.v2.data.AuthenticatorRecord
import com.tpeditz.privatevault.v2.data.BackupExportScope
import com.tpeditz.privatevault.v2.data.BackupPayload
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.data.DocumentRecord
import com.tpeditz.privatevault.v2.data.EncryptedBackupV2Engine
import com.tpeditz.privatevault.v2.data.LegacyV1BackupReader
import com.tpeditz.privatevault.v2.data.MediaRecord
import com.tpeditz.privatevault.v2.data.NoteRecord
import com.tpeditz.privatevault.v2.data.PasswordHistoryEntry
import com.tpeditz.privatevault.v2.data.PrivateVaultDatabase
import com.tpeditz.privatevault.v2.data.RestoreMode
import com.tpeditz.privatevault.v2.data.SecureLinkRecord
import com.tpeditz.privatevault.v2.data.StagedBackupRestore
import com.tpeditz.privatevault.v2.data.VaultAlbumRecord
import com.tpeditz.privatevault.v2.data.VaultFolder
import com.tpeditz.privatevault.v2.data.VaultItem
import com.tpeditz.privatevault.v2.data.VaultRepository
import com.tpeditz.privatevault.v2.data.filterItemsForBackupScope
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.ota.isPendingInstallation
import com.tpeditz.privatevault.v2.policy.AllowlistedFeatureFlags
import com.tpeditz.privatevault.v2.policy.AnnouncementPresentation
import com.tpeditz.privatevault.v2.policy.AnnouncementRecord
import com.tpeditz.privatevault.v2.policy.AnnouncementRepeatMode
import com.tpeditz.privatevault.v2.policy.OtaPolicyScheduler
import com.tpeditz.privatevault.v2.policy.PolicyEvaluator
import com.tpeditz.privatevault.v2.policy.PolicyFeedbackState
import com.tpeditz.privatevault.v2.policy.PolicySyncResult
import com.tpeditz.privatevault.v2.policy.SupabaseFeedbackClient
import com.tpeditz.privatevault.v2.policy.TrustedControlPolicySnapshot
import com.tpeditz.privatevault.v2.security.BiometricKeystoreManager
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordGeneratorEngine
import com.tpeditz.privatevault.v2.security.PasswordPolicy
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import com.tpeditz.privatevault.v2.security.SensitiveClipboardManager
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.security.TotpEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import com.tpeditz.privatevault.v2.sync.AutoCloudBackupKeyStore
import com.tpeditz.privatevault.v2.sync.CloudBackupScheduler
import com.tpeditz.privatevault.v2.sync.GoogleDriveAuthHelper
import com.tpeditz.privatevault.v2.sync.GoogleDriveSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import javax.crypto.Cipher

class VaultViewModel(
    private val context: Context,
    private val repository: VaultRepository,
    private val sessionManager: VaultSessionManager,
    private val preferencesRepository: AppPreferencesRepository,
    private val clipboardManager: SensitiveClipboardManager,
    val otaUpdateManager: OtaUpdateManager,
    val googleDriveSyncManager: GoogleDriveSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    val otaState = otaUpdateManager.otaState
    private var currentStagedRestore: StagedBackupRestore? = null
    private var totpTickerJob: Job? = null
    private var activeBackupJob: Job? = null
    private var loadItemsJob: Job? = null
    @Volatile private var cachedAutoLockTimeoutSeconds: Int = 0
    @Volatile private var cachedLockOnScreenOff = true
    private var activeRestoreJob: Job? = null
    private val backupOpSeq = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeBackupOpId: Long = 0L
    private val restoreOpSeq = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeRestoreOpId: Long = 0L
    private val cloudUploadOpSeq = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeCloudUploadOpId: Long = 0L
    private val activeNoteSaveIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private var foregroundOtaJob: Job? = null
    private var installUpdateJob: Job? = null
    private var lastAutomaticOtaAttemptElapsed: Long? = null
    private var otaForeground = false
    private val _showPendingUpdateReminder = MutableStateFlow(true)
    val showPendingUpdateReminder: StateFlow<Boolean> = _showPendingUpdateReminder.asStateFlow()
    private val _policySnapshot = MutableStateFlow<TrustedControlPolicySnapshot?>(
        otaUpdateManager.currentCachedPolicySnapshot()
    )
    private val _sessionDismissedRepeatAnnouncementIds = MutableStateFlow<Set<String>>(emptySet())
    private var lastDismissedOnceAnnouncementIds: Set<String> = emptySet()
    private val feedbackClient by lazy { SupabaseFeedbackClient() }
    private var submitFeedbackJob: Job? = null

    // Bounded volatile cache for decrypted media thumbnails (12 MiB max), strictly cleared and wiped on eviction, lock, or session change.
    private val thumbnailCacheLock = Any()
    private val decryptedThumbnailCache = object : android.util.LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: ByteArray, newValue: ByteArray?) {
            if (oldValue !== newValue) {
                Arrays.fill(oldValue, 0.toByte())
            }
        }
    }

    fun clearVolatileThumbnailCache() {
        synchronized(thumbnailCacheLock) {
            decryptedThumbnailCache.evictAll()
        }
    }


    init {
        // Collect session state
        var lastActiveSessionId: Long? = null
        viewModelScope.launch {
            sessionManager.sessionState.collect { session ->
                when (session) {
                    is VaultSessionManager.SessionState.Unlocked -> {
                        if (lastActiveSessionId != session.sessionId) {
                            clearVolatileThumbnailCache()
                            loadItemsJob?.cancel()
                            _uiState.update { it.copy(notes = emptyList(), credentials = emptyList(), media = emptyList(),
                                documents = emptyList(), secureLinks = emptyList(), folders = emptyList(), albums = emptyList(),
                                authenticators = emptyList(), pendingSharedText = null, connectedGoogleEmail = "",
                                autoCloudBackupEnabled = false, autoCloudBackupKeyConfigured = false,
                                isCloudRecoveryPasswordLegacyShort = false, lastCloudBackupEpochMs = 0L,
                                lastCloudBackupStatusMessage = null, privacyMetrics = PrivacyHealthMetrics()) }
                        }
                        if (lastActiveSessionId != null && lastActiveSessionId != session.sessionId) {
                            cancelBackup()
                            cancelRestore()
                            clearPendingRestore()
                        }
                        lastActiveSessionId = session.sessionId
                        _uiState.update {
                            it.copy(
                                isUnlocked = true,
                                activeSlotId = session.slotId,
                                isDecoyPolicyConfigured = session.isDecoyConfiguredInRealVault
                            )
                        }
                        loadActiveVaultItems()
                        refreshSystemSecurityStatus()
                        startTotpTicker()
                        refreshOtaOnForeground()
                    }
                    is VaultSessionManager.SessionState.Locked -> {
                        lastActiveSessionId = null
                        _sessionDismissedRepeatAnnouncementIds.value = emptySet()
                        clearVolatileThumbnailCache()
                        loadItemsJob?.cancel()
                        cancelBackup()
                        cancelRestore()
                        clearPendingRestore()
                        totpTickerJob?.cancel()
                        totpTickerJob = null
                        clipboardManager.onLock()
                        _uiState.update {
                            it.copy(
                                isUnlocked = false,
                                activeSlotId = null,
                                isDecoyPolicyConfigured = false,
                                privacyMetrics = PrivacyHealthMetrics(),
                                notes = emptyList(),
                                credentials = emptyList(),
                                media = emptyList(),
                                documents = emptyList(),
                                secureLinks = emptyList(),
                                folders = emptyList(),
                                albums = emptyList(),
                                authenticators = emptyList(),
                                searchQuery = "",
                                pendingSharedText = null,
                                connectedGoogleEmail = "",
                                autoCloudBackupEnabled = false,
                                autoCloudBackupKeyConfigured = false,
                                isCloudRecoveryPasswordLegacyShort = false,
                                lastCloudBackupEpochMs = 0L,
                                lastCloudBackupStatusMessage = null,
                                currentSecondary = SecondaryDestination.None,
                                pendingRestorePayload = null,
                                pendingRestoreError = null
                            )
                        }
                    }
                }
            }
        }

        // Collect canonical DataStore preferences so UI always matches persisted state
        viewModelScope.launch {
            preferencesRepository.themePreference.collect { theme ->
                _uiState.update { it.copy(themePreference = theme) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.screenshotProtectionEnabled.collect { enabled ->
                _uiState.update {
                    it.copy(
                        screenshotProtectionEnabled = enabled,
                        privacyMetrics = it.privacyMetrics.copy(isScreenshotProtected = enabled)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.lockOnScreenOff.collect { enabled ->
                cachedLockOnScreenOff = enabled
                _uiState.update { it.copy(lockOnScreenOff = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.autoLockTimeoutSeconds.collect { sec ->
                cachedAutoLockTimeoutSeconds = sec
                _uiState.update {
                    it.copy(
                        autoLockTimeoutSeconds = sec,
                        privacyMetrics = it.privacyMetrics.copy(autoLockTimeoutSeconds = sec)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.clipboardTtlSeconds.collect { ttl ->
                _uiState.update { it.copy(clipboardTtlSeconds = ttl) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.biometricUnlockEnabled.collect { enabled ->
                val actualEnrolled = enabled && BiometricKeystoreManager.hasKeystoreKey() &&
                    sessionManager.getActiveSlotIdOrNull() != VaultSessionManager.DECOY_SLOT
                _uiState.update {
                    it.copy(
                        isBiometricsConfigured = actualEnrolled,
                        privacyMetrics = it.privacyMetrics.copy(isBiometricEnabled = actualEnrolled)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.autoCloudBackupEnabled.collect { enabled ->
                _uiState.update {
                    it.copy(
                        autoCloudBackupEnabled = enabled && realSessionId() != null,
                        privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = enabled && realSessionId() != null)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.cloudBackupFrequency.collect { freq ->
                _uiState.update { it.copy(cloudBackupFrequency = freq) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.cloudBackupWifiOnly.collect { wifiOnly ->
                _uiState.update { it.copy(cloudBackupWifiOnly = wifiOnly) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.connectedGoogleAccount.collect { storedEmail ->
                val hasRecoveryPwd = AutoCloudBackupKeyStore.hasRecoveryPassword(context)
                val legacyShort = computeIsCloudRecoveryLegacyShort()
                _uiState.update {
                    it.copy(
                        connectedGoogleEmail = if (realSessionId() != null) storedEmail.trim() else "",
                        autoCloudBackupKeyConfigured = hasRecoveryPwd && realSessionId() != null,
                        isCloudRecoveryPasswordLegacyShort = legacyShort && realSessionId() != null
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.lastCloudBackupEpochMs.collect { epoch ->
                _uiState.update {
                    it.copy(
                        lastCloudBackupEpochMs = if (realSessionId() != null) epoch else 0L,
                        privacyMetrics = it.privacyMetrics.copy(lastCloudBackupEpochMs = if (realSessionId() != null) epoch else 0L)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.lastLocalBackupEpochMs.collect { epoch ->
                _uiState.update {
                    it.copy(privacyMetrics = it.privacyMetrics.copy(lastLocalBackupEpochMs = epoch))
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.autoCloudBackupStatus.collect { status ->
                _uiState.update { it.copy(lastCloudBackupStatusMessage = if (realSessionId() != null) status.ifBlank { null } else null) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.automaticOtaCheckEnabled.collect { enabled ->
                _uiState.update { it.copy(automaticOtaCheckEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.lastOtaCheckEpochMs.collect { epoch ->
                _uiState.update { it.copy(lastOtaCheckEpochMs = epoch) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.betaChannelOptIn.collect { optIn ->
                _uiState.update { it.copy(betaChannelOptIn = optIn) }
                recomputeControlCenterPolicyUi()
            }
        }
        viewModelScope.launch {
            preferencesRepository.dismissedAnnouncementIds.collect { ids ->
                lastDismissedOnceAnnouncementIds = ids
                recomputeControlCenterPolicyUi()
            }
        }
        viewModelScope.launch {
            preferencesRepository.lastPolicySyncEpochMs.collect { epoch ->
                _uiState.update { it.copy(lastPolicySyncEpochMs = epoch) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.effectiveOtaCheckIntervalHours.collect { hours ->
                _uiState.update { it.copy(otaCheckIntervalHours = hours) }
            }
        }
        viewModelScope.launch {
            _policySnapshot.collect {
                recomputeControlCenterPolicyUi()
            }
        }
        viewModelScope.launch {
            otaUpdateManager.otaState.collect { ota ->
                val available = ota is OtaState.UpdateAvailable || ota is OtaState.ReadyToInstall || ota is OtaState.NeedsUnknownSourcesPermission
                _uiState.update {
                    it.copy(privacyMetrics = it.privacyMetrics.copy(isUpdateAvailable = available))
                }
            }
        }

        // Collect clipboard countdown
        viewModelScope.launch {
            clipboardManager.activeCountdownSeconds.collect { sec ->
                _uiState.update { it.copy(clipboardCountdownSeconds = sec) }
            }
        }

        // Check if vault is initialized and sync live Android system state
        viewModelScope.launch {
            val initialized = repository.isVaultInitialized()
            _uiState.update { it.copy(isVaultInitialized = initialized) }
            refreshSystemSecurityStatus()
        }
    }

    // ============================================================================================
    // Navigation & Tabs
    // ============================================================================================

    fun navigateToTab(tab: PrimaryTab) {
        _uiState.update {
            it.copy(currentTab = tab, currentSecondary = SecondaryDestination.None)
        }
    }

    fun navigateToSecondary(destination: SecondaryDestination) {
        _uiState.update { it.copy(currentSecondary = destination) }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value.currentSecondary
        return if (current !is SecondaryDestination.None) {
            _uiState.update { it.copy(currentSecondary = SecondaryDestination.None) }
            true
        } else {
            false
        }
    }

    // ============================================================================================
    // Lifecycle & Security Guards
    // ============================================================================================

    fun onScreenTurnedOff() {
        if (cachedLockOnScreenOff) lockVault()
    }

    fun onAppBackgrounded() {
        otaForeground = false
        sessionManager.onAppBackgrounded()
    }

    fun onAppForegrounded() {
        otaForeground = true
        sessionManager.onAppForegrounded(cachedAutoLockTimeoutSeconds)
        clipboardManager.onAppForegrounded()
    }

    fun grantFilePickerImmunity() {
        sessionManager.grantFilePickerImmunity()
    }

    fun clearFilePickerImmunity() = sessionManager.clearFilePickerImmunity()
    fun canEnrollBiometrics(): Boolean {
        val session = sessionManager.getActiveSession() ?: return false
        return session.slotId == VaultSessionManager.REAL_SLOT && !session.isDecoyConfiguredInRealVault
    }

    private fun realSessionId(): Long? =
        sessionManager.getActiveSession()?.takeIf { it.slotId == VaultSessionManager.REAL_SLOT }?.sessionId

    private fun requireRealSession(expectedId: Long? = null): Long {
        val id = realSessionId() ?: throw IllegalStateException("Unlock your main vault to manage cloud backup.")
        check(expectedId == null || id == expectedId) { "Vault session changed. Unlock and try again." }
        return id
    }

    // ============================================================================================
    // Vault Setup & Unlock
    // ============================================================================================

    fun createVault(passwordChars: CharArray, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = repository.createInitialVault(passwordChars)
            val nowInitialized = res is AuthResult.Success || repository.isVaultInitialized()
            _uiState.update { it.copy(isLoading = false, isVaultInitialized = nowInitialized) }
            when (res) {
                is AuthResult.Success -> onResult(true, null)
                is AuthResult.Error -> onResult(false, res.message)
                else -> onResult(false, "Initialization failed")
            }
        }
    }

    fun unlockWithPassword(passwordChars: CharArray, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = repository.unlockVault(passwordChars)
            _uiState.update { it.copy(isLoading = false) }
            when (res) {
                is AuthResult.Success -> onResult(true, null)
                is AuthResult.IncorrectPassword, is AuthResult.InvalidPassword -> onResult(false, "Incorrect master password.")
                is AuthResult.Error -> onResult(false, res.message)
                is AuthResult.LockedOut -> onResult(false, "Too many failed attempts. Try again in ${res.remainingSeconds}s.")
            }
        }
    }

    suspend fun getBiometricIvForUnlock(): ByteArray? {
        return repository.getBiometricIvForUnlock()
    }

    private suspend fun reconcileBiometricPreferencesAndKeystore(): String? {
        val dbNonceSha = repository.getBiometricNonceSha256Hex()
        val activeAlias = preferencesRepository.biometricKeyAlias.first()
        val pendingAlias = preferencesRepository.biometricPendingAlias.first()
        val pendingNonceSha = preferencesRepository.biometricPendingNonceSha256.first()
        val resolution = BiometricKeystoreManager.reconcileAliasState(
            activeAlias = activeAlias,
            pendingAlias = pendingAlias,
            pendingNonceSha256 = pendingNonceSha,
            dbBiometricNonceSha256 = dbNonceSha,
            hasKeyInKeystore = { alias -> BiometricKeystoreManager.hasKeystoreKey(alias) }
        )
        if (dbNonceSha == null) {
            preferencesRepository.clearBiometricPreferences()
            BiometricKeystoreManager.deleteBiometricKey()
            return null
        }
        if (resolution.shouldPromotePendingAlias && !resolution.unlockAlias.isNullOrBlank()) {
            preferencesRepository.commitPendingBiometricAlias(resolution.unlockAlias)
        } else if (resolution.shouldClearPending) {
            preferencesRepository.clearPendingBiometricCommit()
        }
        resolution.staleAliasToRetire?.let { BiometricKeystoreManager.retireAlias(it) }
        return resolution.unlockAlias
    }

    suspend fun getBiometricUnlockInfo(): Pair<ByteArray, String?>? {
        val resolvedAlias = reconcileBiometricPreferencesAndKeystore()
        val iv = repository.getBiometricIvForUnlock() ?: return null
        return iv to resolvedAlias
    }

    suspend fun getActiveBiometricAlias(): String? {
        return reconcileBiometricPreferencesAndKeystore()
            ?: preferencesRepository.biometricKeyAlias.first()
    }

    suspend fun enrollBiometrics(cipher: Cipher, targetAlias: String? = null): Boolean {
        var dbCommitted = false
        return try {
            val ok = repository.enrollBiometricKey(cipher) { nonceSha256 ->
                if (!targetAlias.isNullOrBlank()) {
                    preferencesRepository.stagePendingBiometricCommit(targetAlias, nonceSha256)
                }
            }
            if (ok) {
                dbCommitted = true
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!targetAlias.isNullOrBlank()) {
                        preferencesRepository.commitPendingBiometricAlias(targetAlias)
                        BiometricKeystoreManager.commitStagedAliasAndRetirePrevious(targetAlias)
                    } else {
                        preferencesRepository.setBiometricUnlockEnabled(true)
                    }
                }
                _uiState.update {
                    it.copy(
                        isBiometricsConfigured = true,
                        privacyMetrics = it.privacyMetrics.copy(isBiometricEnabled = true)
                    )
                }
            } else if (!targetAlias.isNullOrBlank()) {
                preferencesRepository.clearPendingBiometricCommit()
                BiometricKeystoreManager.discardStagedAlias(targetAlias)
            }
            ok
        } catch (t: Throwable) {
            if (!dbCommitted && !targetAlias.isNullOrBlank()) {
                runCatching { preferencesRepository.clearPendingBiometricCommit() }
                BiometricKeystoreManager.discardStagedAlias(targetAlias)
            }
            if (t is CancellationException) throw t
            false
        }
    }

    fun unlockWithBiometric(cipher: Cipher, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = repository.unlockWithBiometric(cipher)
            _uiState.update { it.copy(isLoading = false) }
            when (res) {
                is com.tpeditz.privatevault.v2.data.AuthResult.Success -> {
                    loadActiveVaultItems()
                    onResult(true, null)
                }
                is com.tpeditz.privatevault.v2.data.AuthResult.Error -> onResult(false, res.message)
                else -> onResult(false, "Biometric authentication failed")
            }
        }
    }

    fun lockVault() {
        sessionManager.lockNow()
    }

    // ============================================================================================
    // Item Loading & Reactive Metrics
    // ============================================================================================

    fun loadActiveVaultItems() {
        val expectedSessionId = sessionManager.getCurrentSessionId() ?: return
        loadItemsJob?.cancel()
        loadItemsJob = viewModelScope.launch {
            val auditResult = try {
                repository.getAllItemsForActiveSlotWithAudit(expectedSessionId)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            if (sessionManager.getCurrentSessionId() != expectedSessionId) return@launch
            val items = auditResult.items

            val notes = items.filterIsInstance<NoteRecord>()
            val credentials = items.filterIsInstance<CredentialRecord>()
            val media = items.filterIsInstance<MediaRecord>()
            val documents = items.filterIsInstance<DocumentRecord>()
            val links = items.filterIsInstance<SecureLinkRecord>()
            val folders = items.filterIsInstance<VaultFolder>()
            val albums = items.filterIsInstance<VaultAlbumRecord>()
            val authenticators = items.filterIsInstance<AuthenticatorRecord>()

            // Compute password strength audit metrics
            val passwordsList = credentials.filter { !it.isTrashed && !it.isArchived }.map { it.password }
            val reuseMap = OfflinePasswordStrengthEstimator.computeVaultReuseFrequencyMap(passwordsList)
            val reusedSet = reuseMap.filter { it.value > 1 }.keys

            var weakCount = 0
            var reusedCount = 0
            var scoreSum = 0

            passwordsList.forEach { pwd ->
                val report = OfflinePasswordStrengthEstimator.evaluate(pwd, existingVaultPasswords = reusedSet)
                scoreSum += report.score
                if (report.band == OfflinePasswordStrengthEstimator.StrengthBand.CRITICAL ||
                    report.band == OfflinePasswordStrengthEstimator.StrengthBand.WEAK
                ) {
                    weakCount++
                }
                if (reusedSet.contains(pwd)) {
                    reusedCount++
                }
            }

            val avgScore = if (passwordsList.isNotEmpty()) scoreSum / passwordsList.size else 0
            val lastLocal = preferencesRepository.lastLocalBackupEpochMs.first()
            val real = realSessionId() == expectedSessionId
            val lastCloud = if (real) preferencesRepository.lastCloudBackupEpochMs.first() else 0L
            val autoBackup = real && preferencesRepository.autoCloudBackupEnabled.first()
            val bio = real && preferencesRepository.biometricUnlockEnabled.first() &&
                BiometricKeystoreManager.hasKeystoreKey()
            val screenSec = preferencesRepository.screenshotProtectionEnabled.first()
            val timeoutSec = preferencesRepository.autoLockTimeoutSeconds.first()

            val currentOta = otaUpdateManager.otaState.value
            val updateAvailable = currentOta is OtaState.UpdateAvailable ||
                currentOta is OtaState.ReadyToInstall ||
                currentOta is OtaState.NeedsUnknownSourcesPermission

            val hasCloudRecoveryKey = real && AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            val metrics = PrivacyHealthMetrics(
                totalItemsCount = items.size,
                averagePasswordScore = avgScore,
                weakPasswordsCount = weakCount,
                reusedPasswordsCount = reusedCount,
                lastLocalBackupEpochMs = lastLocal,
                lastCloudBackupEpochMs = lastCloud,
                isAutoBackupEnabled = autoBackup,
                isBiometricEnabled = bio,
                isScreenshotProtected = screenSec,
                autoLockTimeoutSeconds = timeoutSec,
                isUpdateAvailable = updateAvailable
            )

            ensureActive()
            sessionManager.runIfCurrentSession(expectedSessionId) {
              _uiState.update {
                it.copy(
                    isBiometricsConfigured = bio,
                    autoCloudBackupKeyConfigured = hasCloudRecoveryKey,
                    notes = notes,
                    credentials = credentials,
                    media = media,
                    documents = documents,
                    secureLinks = links,
                    folders = folders,
                    albums = albums,
                    authenticators = authenticators,
                    privacyMetrics = metrics
                )
              }
            }
            preparePendingAutoCloudBackupIfNeeded()
        }
    }

    // ============================================================================================
    // Notes CRUD
    // ============================================================================================

    fun saveNote(
        note: NoteRecord,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val expectedSessionId = sessionManager.getCurrentSessionId()
        if (expectedSessionId == null || !_uiState.value.isUnlocked) {
            onComplete?.invoke(false, "Your vault was locked. Unlock it and try again.")
            return
        }
        if (!activeNoteSaveIds.add(note.id)) {
            onComplete?.invoke(false, "Save already in progress.")
            return
        }
        viewModelScope.launch {
            try {
                repository.saveItem(
                    item = note.copy(updatedAtEpochMs = System.currentTimeMillis()),
                    expectedSessionId = expectedSessionId
                )
                if (sessionManager.getCurrentSessionId() != expectedSessionId) {
                    onComplete?.invoke(false, "Your vault was locked before saving finished.")
                    return@launch
                }
                loadActiveVaultItems()
                onComplete?.invoke(true, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onComplete?.invoke(false, VaultErrorMapper.mapVaultActionError(e))
            } finally {
                activeNoteSaveIds.remove(note.id)
            }
        }
    }

    fun toggleChecklistItem(noteId: String, entryId: String) {
        val note = _uiState.value.notes.find { it.id == noteId } ?: return
        val updatedEntries = note.checklistItems.map {
            if (it.id == entryId) it.copy(isChecked = !it.isChecked) else it
        }
        saveNote(note.copy(checklistItems = updatedEntries))
    }

    fun deleteNote(noteId: String, permanent: Boolean = false) {
        viewModelScope.launch {
            if (permanent) {
                repository.deleteItem(noteId)
            } else {
                val note = _uiState.value.notes.find { it.id == noteId } ?: return@launch
                repository.saveItem(note.copy(isTrashed = true, updatedAtEpochMs = System.currentTimeMillis()))
            }
            loadActiveVaultItems()
        }
    }

    fun restoreNoteFromTrash(noteId: String) {
        viewModelScope.launch {
            val note = _uiState.value.notes.find { it.id == noteId } ?: return@launch
            repository.saveItem(note.copy(isTrashed = false, updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
        }
    }

    // ============================================================================================
    // Credentials CRUD & History
    // ============================================================================================

    fun saveCredential(cred: CredentialRecord) {
        viewModelScope.launch {
            repository.saveItem(cred.copy(updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
        }
    }

    fun deleteCredential(id: String, permanent: Boolean = false) {
        viewModelScope.launch {
            if (permanent) {
                repository.deleteItem(id)
            } else {
                val cred = _uiState.value.credentials.find { it.id == id } ?: return@launch
                repository.saveItem(cred.copy(isTrashed = true, updatedAtEpochMs = System.currentTimeMillis()))
            }
            loadActiveVaultItems()
        }
    }

    fun restoreCredentialFromTrash(id: String) {
        viewModelScope.launch {
            val cred = _uiState.value.credentials.find { it.id == id } ?: return@launch
            repository.saveItem(cred.copy(isTrashed = false, updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
        }
    }

    fun restorePreviousPassword(credentialId: String, historyEntry: PasswordHistoryEntry) {
        val cred = _uiState.value.credentials.find { it.id == credentialId } ?: return
        saveCredential(cred.copy(password = historyEntry.password))
    }

    fun copySensitivePassword(label: String, password: String) {
        viewModelScope.launch {
            val ttl = preferencesRepository.clipboardTtlSeconds.first()
            clipboardManager.copySensitiveSecret(label, password, ttl)
        }
    }

    fun copyPublicText(label: String, text: String) {
        clipboardManager.copyPublicText(label, text)
    }

    fun importCsv(inputStream: InputStream, onComplete: (Int, Int, String?) -> Unit) {
        val expectedSessionId = sessionManager.getCurrentSessionId()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                inputStream.use { stream ->
                    if (expectedSessionId == null) {
                        withContext(Dispatchers.Main) {
                            onComplete(0, 0, "Unlock your vault before importing credentials.")
                        }
                        return@launch
                    }
                    val res = repository.importCredentialsFromCsv(stream, expectedSessionId = expectedSessionId)
                    loadActiveVaultItems()
                    withContext(Dispatchers.Main) { onComplete(res.first, res.second, null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val friendly = when {
                    e.message.orEmpty().contains("session", ignoreCase = true) ||
                        e.message.orEmpty().contains("locked", ignoreCase = true) ->
                        "Import stopped because the vault session changed or locked."
                    e is IllegalArgumentException && !e.message.isNullOrBlank() -> e.message!!
                    else -> "Could not import CSV file. Check the file format and try again."
                }
                withContext(Dispatchers.Main) { onComplete(0, 0, friendly) }
            }
        }
    }

    // ============================================================================================
    // Media, Documents, Secure Links, Albums
    // ============================================================================================

    fun saveLink(link: SecureLinkRecord) {
        viewModelScope.launch {
            repository.saveItem(link.copy(updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
        }
    }

    fun deleteLink(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
            loadActiveVaultItems()
        }
    }

    fun createAlbum(name: String) {
        viewModelScope.launch {
            val album = VaultAlbumRecord(name = name)
            repository.saveItem(album)
            loadActiveVaultItems()
        }
    }

    fun assignMediaToAlbum(mediaId: String, albumId: String) {
        viewModelScope.launch {
            val media = _uiState.value.media.find { it.id == mediaId } ?: return@launch
            repository.saveItem(media.copy(albumId = albumId))
            loadActiveVaultItems()
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            repository.deleteItem(albumId)
            loadActiveVaultItems()
        }
    }

    fun deleteMedia(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
            loadActiveVaultItems()
        }
    }

    fun setPendingSharedText(rawText: String?) {
        val sanitized = rawText?.trim()?.take(100_000).orEmpty()
        if (sanitized.isNotBlank()) {
            _uiState.update { it.copy(pendingSharedText = sanitized) }
        }
    }

    fun clearPendingSharedText() {
        _uiState.update { it.copy(pendingSharedText = null) }
    }

    fun saveSharedTextAsNote(
        title: String,
        body: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val expectedSessionId = sessionManager.getCurrentSessionId() ?: run {
            onComplete(false, "Your vault is locked. Unlock it and try again.")
            return
        }
        viewModelScope.launch {
            try {
                if (sessionManager.getCurrentSessionId() != expectedSessionId) {
                    onComplete(false, "Your vault was locked. Unlock it and try again.")
                    return@launch
                }
                val cleanBody = body.trim().take(100_000)
                if (cleanBody.isBlank()) {
                    onComplete(false, "Cannot save an empty shared item.")
                    return@launch
                }
                val resolvedTitle = title.trim().ifBlank {
                    cleanBody.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Shared Note"
                }
                val now = System.currentTimeMillis()
                repository.saveItem(
                    NoteRecord(
                        title = resolvedTitle,
                        body = cleanBody,
                        category = "Shared",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                loadActiveVaultItems()
                _uiState.update {
                    it.copy(
                        pendingSharedText = null,
                        activeSnackbarMessage = "Saved shared text as note"
                    )
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, VaultErrorMapper.mapVaultActionError(e))
            }
        }
    }

    fun saveSharedTextAsLink(
        title: String,
        url: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val expectedSessionId = sessionManager.getCurrentSessionId() ?: run {
            onComplete(false, "Your vault is locked. Unlock it and try again.")
            return
        }
        viewModelScope.launch {
            try {
                if (sessionManager.getCurrentSessionId() != expectedSessionId) {
                    onComplete(false, "Your vault was locked. Unlock it and try again.")
                    return@launch
                }
                val cleanUrl = url.trim().take(4_096)
                if (cleanUrl.isBlank()) {
                    onComplete(false, "Please enter a valid link or URL.")
                    return@launch
                }
                val resolvedTitle = title.trim().ifBlank {
                    runCatching { android.net.Uri.parse(cleanUrl).host }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                        ?: "Shared Link"
                }
                val now = System.currentTimeMillis()
                repository.saveItem(
                    SecureLinkRecord(
                        title = resolvedTitle,
                        url = cleanUrl,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                loadActiveVaultItems()
                _uiState.update {
                    it.copy(
                        pendingSharedText = null,
                        activeSnackbarMessage = "Saved shared link to vault"
                    )
                }
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, VaultErrorMapper.mapVaultActionError(e))
            }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
            loadActiveVaultItems()
        }
    }

    // ============================================================================================
    // Media & Documents Import & Crypto Viewers
    // ============================================================================================

    fun importVisualMedia(
        uris: List<Uri>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                onProgress(index, total)
                try {
                    repository.importVisualMediaFromUri(uri)
                    imported++
                } catch (e: Exception) {
                    SafeVaultLogger.e("Media", "Import failed for $uri", e)
                    failed++
                }
            }
            loadActiveVaultItems()
            withContext(Dispatchers.Main) { onComplete(imported, failed) }
        }
    }

    fun importDocuments(
        uris: List<Uri>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onComplete: (Int, Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                onProgress(index, total)
                try {
                    repository.importDocumentFromUri(uri)
                    imported++
                } catch (e: Exception) {
                    SafeVaultLogger.e("Document", "Import failed for $uri", e)
                    failed++
                }
            }
            loadActiveVaultItems()
            withContext(Dispatchers.Main) { onComplete(imported, failed) }
        }
    }

    suspend fun loadDecryptedPhotoBytes(mediaRecord: MediaRecord): ByteArray {
        return repository.loadDecryptedPhotoBytes(mediaRecord)
    }

    suspend fun loadDecryptedThumbnailBytes(mediaRecord: MediaRecord): ByteArray? {
        val initialSession = sessionManager.getActiveSession() ?: return null
        if (initialSession.isWiped) return null
        val activeSessionId = initialSession.sessionId
        val cacheKey = "${activeSessionId}_${mediaRecord.id}"
        synchronized(thumbnailCacheLock) {
            val stillValid = sessionManager.getActiveSession()
            if (stillValid == null || stillValid.sessionId != activeSessionId || stillValid.isWiped) {
                return null
            }
            decryptedThumbnailCache.get(cacheKey)?.let { return it.copyOf() }
        }
        val bytes = runCatching { repository.loadDecryptedThumbnailBytes(mediaRecord) }.getOrNull() ?: return null
        val postLoadSession = sessionManager.getActiveSession()
        if (postLoadSession == null || postLoadSession.sessionId != activeSessionId || postLoadSession.isWiped) {
            Arrays.fill(bytes, 0.toByte())
            return null
        }
        if (bytes.size <= 2 * 1024 * 1024) {
            synchronized(thumbnailCacheLock) {
                val insideLockSession = sessionManager.getActiveSession()
                if (insideLockSession != null && insideLockSession.sessionId == activeSessionId && !insideLockSession.isWiped) {
                    decryptedThumbnailCache.put(cacheKey, bytes)
                    return bytes.copyOf()
                }
            }
            Arrays.fill(bytes, 0.toByte())
            return null
        }
        return bytes
    }

    suspend fun openEncryptedVideoDataSource(mediaRecord: MediaRecord): StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource {
        return repository.openEncryptedVideoDataSource(mediaRecord)
    }

    suspend fun loadDecryptedDocumentBytes(documentRecord: DocumentRecord): ByteArray {
        return repository.loadDecryptedDocumentBytes(documentRecord)
    }

    suspend fun loadDecryptedDocumentPreviewBytes(
        documentRecord: DocumentRecord,
        maxPreviewBytes: Int = 2 * 1024 * 1024
    ): ByteArray {
        return repository.loadDecryptedDocumentPreviewBytes(documentRecord, maxPreviewBytes)
    }

    suspend fun exportDecryptedDocumentToStream(
        documentRecord: DocumentRecord,
        outputStream: OutputStream,
        isCancelled: () -> Boolean = { false }
    ): Long {
        val expectedSessionId = sessionManager.getCurrentSessionId()
        return repository.exportDecryptedDocumentToStream(
            documentRecord = documentRecord,
            outputStream = outputStream,
            expectedSessionId = expectedSessionId,
            isCancelled = isCancelled
        )
    }

    // ============================================================================================
    // Settings, Security & Decoy Provisioning
    // ============================================================================================

    fun setScreenshotProtection(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                preferencesRepository.setScreenshotProtectionEnabled(enabled)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        screenshotProtectionEnabled = enabled,
                        privacyMetrics = it.privacyMetrics.copy(isScreenshotProtected = enabled)
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(activeSnackbarMessage = "Could not save screenshot protection preference.") }
            }
        }
    }

    fun setLockOnScreenOff(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                preferencesRepository.setLockOnScreenOff(enabled)
            }.onSuccess {
                _uiState.update { it.copy(lockOnScreenOff = enabled) }
            }.onFailure {
                _uiState.update { it.copy(activeSnackbarMessage = "Could not save screen-off lock preference.") }
            }
        }
    }

    fun setAutoLockTimeout(seconds: Int) {
        viewModelScope.launch {
            runCatching {
                preferencesRepository.setAutoLockTimeoutSeconds(seconds)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        autoLockTimeoutSeconds = seconds,
                        privacyMetrics = it.privacyMetrics.copy(autoLockTimeoutSeconds = seconds)
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(activeSnackbarMessage = "Could not save auto-lock timeout.") }
            }
        }
    }

    fun setClipboardTtl(seconds: Int) {
        viewModelScope.launch {
            runCatching {
                preferencesRepository.setClipboardTtlSeconds(seconds)
            }.onSuccess {
                _uiState.update { it.copy(clipboardTtlSeconds = seconds) }
            }.onFailure {
                _uiState.update { it.copy(activeSnackbarMessage = "Could not save clipboard timer preference.") }
            }
        }
    }

    private fun computeIsCloudRecoveryLegacyShort(): Boolean {
        if (realSessionId() == null) return false
        val chars = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(context) ?: return false
        return try {
            PasswordPolicy.isShortLegacyPassword(chars)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    fun refreshSystemSecurityStatus() {
        viewModelScope.launch {
            val reconciledAlias = reconcileBiometricPreferencesAndKeystore()
            val bioPref = preferencesRepository.biometricUnlockEnabled.first()
            val actualBio = bioPref && !reconciledAlias.isNullOrBlank() &&
                BiometricKeystoreManager.hasKeystoreKey(reconciledAlias) &&
                sessionManager.getActiveSlotIdOrNull() != VaultSessionManager.DECOY_SLOT
            val storedEmail = preferencesRepository.connectedGoogleAccount.first().trim()
            val hasRecoveryPwd = AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            val autoEnabled = preferencesRepository.autoCloudBackupEnabled.first()
            val lastCloudStatus = preferencesRepository.autoCloudBackupStatus.first()
            val legacyShort = withContext(Dispatchers.IO) { computeIsCloudRecoveryLegacyShort() }
            val currentOta = otaUpdateManager.otaState.value
            val updateAvailable = currentOta is OtaState.UpdateAvailable ||
                currentOta is OtaState.ReadyToInstall ||
                currentOta is OtaState.NeedsUnknownSourcesPermission
            _uiState.update {
                it.copy(
                    isBiometricsConfigured = actualBio,
                    connectedGoogleEmail = if (realSessionId() != null) storedEmail else "",
                    autoCloudBackupKeyConfigured = hasRecoveryPwd && realSessionId() != null,
                    isCloudRecoveryPasswordLegacyShort = legacyShort && realSessionId() != null,
                    autoCloudBackupEnabled = realSessionId() != null && autoEnabled,
                    lastCloudBackupStatusMessage = if (realSessionId() != null) lastCloudStatus.ifBlank { null } else null,
                    privacyMetrics = it.privacyMetrics.copy(
                        isBiometricEnabled = actualBio,
                        isUpdateAvailable = updateAvailable
                    )
                )
            }
        }
    }

    fun disableBiometrics() {
        if (realSessionId() == null) return
        viewModelScope.launch {
            repository.disableBiometricKey()
            preferencesRepository.clearBiometricPreferences()
            _uiState.update {
                it.copy(
                    isBiometricsConfigured = false,
                    privacyMetrics = it.privacyMetrics.copy(isBiometricEnabled = false)
                )
            }
        }
    }

    fun provisionDecoy(primaryPasswordChars: CharArray, decoyPasswordChars: CharArray, onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val ok = repository.provisionDecoyVault(primaryPasswordChars, decoyPasswordChars)
                if (ok) {
                    preferencesRepository.clearBiometricPreferences()
                    _uiState.update {
                        it.copy(
                            isBiometricsConfigured = false,
                            privacyMetrics = it.privacyMetrics.copy(isBiometricEnabled = false)
                        )
                    }
                }
                loadActiveVaultItems()
                withContext(Dispatchers.Main) { onComplete?.invoke(ok, if (ok) null else "Check your primary password and use at least 12 characters for your decoy password.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete?.invoke(false, VaultErrorMapper.mapVaultActionError(e)) }
            } finally {
                primaryPasswordChars.fill('\u0000')
                decoyPasswordChars.fill('\u0000')
            }
        }
    }

    fun disableDecoy(primaryPasswordChars: CharArray, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val ok = repository.disableDecoyVault(primaryPasswordChars)
                loadActiveVaultItems()
                withContext(Dispatchers.Main) { onComplete?.invoke(ok) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete?.invoke(false) }
            }
        }
    }

    fun setTheme(theme: com.tpeditz.privatevault.v2.data.ThemePreference) {
        viewModelScope.launch {
            preferencesRepository.setTheme(theme)
            _uiState.update { it.copy(themePreference = theme) }
        }
    }

    // ============================================================================================
    // Cloud Backup & Snapshots
    // ============================================================================================

    private var pendingAutoPrepareJob: Job? = null

    fun authorizeGoogleDrive(
        email: String? = null,
        onResult: (GoogleDriveAuthHelper.DriveAuthState) -> Unit
    ) {
        viewModelScope.launch {
            if (realSessionId() == null) {
                onResult(GoogleDriveAuthHelper.DriveAuthState.Failed("Unlock your main vault to connect Google Drive."))
                return@launch
            }
            val targetEmail = email?.trim()?.takeIf { it.isNotBlank() }
                ?: preferencesRepository.connectedGoogleAccount.first().trim()
            val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, targetEmail)
            onResult(authState)
        }
    }

    fun onGoogleAccountConnected(email: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireRealSession()
                val cleanEmail = email.trim()
                preferencesRepository.setConnectedGoogleAccount(cleanEmail)
                preferencesRepository.setAutoCloudBackupStatus("Connected to $cleanEmail")
                val autoEnabled = preferencesRepository.autoCloudBackupEnabled.first()
                if (autoEnabled && AutoCloudBackupKeyStore.hasRecoveryPassword(context)) {
                    val freq = preferencesRepository.cloudBackupFrequency.first()
                    val wifiOnly = preferencesRepository.cloudBackupWifiOnly.first()
                    CloudBackupScheduler.schedulePeriodicBackup(context, freq, wifiOnly)
                    preparePendingAutoCloudBackupIfNeeded(forceImmediateTrigger = false)
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            connectedGoogleEmail = cleanEmail,
                            lastCloudBackupStatusMessage = "Connected to $cleanEmail"
                        )
                    }
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                SafeVaultLogger.w("VaultViewModel", "Failed to persist Google account: ${e.message}")
                withContext(Dispatchers.Main) { onComplete?.invoke(false) }
            }
        }
    }

    fun disconnectGoogleAccount(onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireRealSession()
                CloudBackupScheduler.cancelAllCloudWork(context)
                CloudBackupScheduler.clearPendingSnapshots(context)
                AutoCloudBackupKeyStore.clearRecoveryPassword(context)
                preferencesRepository.setAutoCloudBackupEnabled(false)
                preferencesRepository.setConnectedGoogleAccount("")
                preferencesRepository.setAutoCloudBackupStatus("Not connected")
                GoogleDriveAuthHelper.signOut(context)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            connectedGoogleEmail = "",
                            autoCloudBackupEnabled = false,
                            autoCloudBackupKeyConfigured = false,
                            isCloudRecoveryPasswordLegacyShort = false,
                            lastCloudBackupStatusMessage = "Not connected",
                            privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = false)
                        )
                    }
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, VaultErrorMapper.mapCloudError(e)) }
            }
        }
    }

    fun configureAutomaticCloudBackup(
        enabled: Boolean,
        recoveryPasswordChars: CharArray?,
        onConsentRequired: ((PendingIntent) -> Unit)? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                requireRealSession()
                if (enabled) {
                    val connectedEmail = preferencesRepository.connectedGoogleAccount.first().trim()
                    if (connectedEmail.isBlank()) {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Connect a Google account first.")
                        }
                    }
                    when (val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, connectedEmail)) {
                        is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                            withContext(Dispatchers.Main) {
                                onConsentRequired?.invoke(authState.pendingIntent)
                                onComplete(false, "Approve Google Drive permission to finish setup.")
                            }
                            return@launch
                        }
                        is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                            return@launch withContext(Dispatchers.Main) {
                                onComplete(false, authState.userMessage)
                            }
                        }
                        is GoogleDriveAuthHelper.DriveAuthState.Authorized -> Unit
                    }

                    if (recoveryPasswordChars != null) {
                        val policy = PasswordPolicy.validateNewPassword(recoveryPasswordChars, "Recovery password")
                        if (!policy.isValid) {
                            return@launch withContext(Dispatchers.Main) {
                                onComplete(false, policy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters.")
                            }
                        }
                        val stored = AutoCloudBackupKeyStore.storeRecoveryPassword(context, recoveryPasswordChars)
                        if (!stored) {
                            return@launch withContext(Dispatchers.Main) {
                                onComplete(false, "Could not protect recovery password on this device.")
                            }
                        }
                    } else if (!AutoCloudBackupKeyStore.hasRecoveryPassword(context)) {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Please enter a backup recovery password first.")
                        }
                    }
                    val freq = preferencesRepository.cloudBackupFrequency.first()
                    val wifiOnly = preferencesRepository.cloudBackupWifiOnly.first()
                    val legacyShort = computeIsCloudRecoveryLegacyShort()
                    preferencesRepository.setConnectedGoogleAccount(connectedEmail)
                    preferencesRepository.setAutoCloudBackupEnabled(true)
                    preferencesRepository.setAutoCloudBackupStatus("Automatic backup scheduled")
                    CloudBackupScheduler.schedulePeriodicBackup(context, freq, wifiOnly)
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                autoCloudBackupEnabled = true,
                                autoCloudBackupKeyConfigured = true,
                                isCloudRecoveryPasswordLegacyShort = legacyShort,
                                lastCloudBackupStatusMessage = "Automatic backup scheduled",
                                privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = true)
                            )
                        }
                        onComplete(true, null)
                    }
                    preparePendingAutoCloudBackupIfNeeded(forceImmediateTrigger = true)
                } else {
                    CloudBackupScheduler.cancelAllCloudWork(context)
                    CloudBackupScheduler.clearPendingSnapshots(context)
                    AutoCloudBackupKeyStore.clearRecoveryPassword(context)
                    preferencesRepository.setAutoCloudBackupEnabled(false)
                    preferencesRepository.setAutoCloudBackupStatus("Automatic backup turned off")
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                autoCloudBackupEnabled = false,
                                autoCloudBackupKeyConfigured = false,
                                isCloudRecoveryPasswordLegacyShort = false,
                                lastCloudBackupStatusMessage = "Automatic backup turned off",
                                privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = false)
                            )
                        }
                        onComplete(true, null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, VaultErrorMapper.mapCloudError(e)) }
            } finally {
                if (recoveryPasswordChars != null) {
                    Arrays.fill(recoveryPasswordChars, '\u0000')
                }
            }
        }
    }

    fun setCloudBackupFrequency(frequency: CloudBackupFrequency) {
        if (realSessionId() == null) return
        viewModelScope.launch(Dispatchers.IO) {
            preferencesRepository.setCloudBackupFrequency(frequency)
            _uiState.update { it.copy(cloudBackupFrequency = frequency) }
            if (preferencesRepository.autoCloudBackupEnabled.first() &&
                AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            ) {
                val wifiOnly = preferencesRepository.cloudBackupWifiOnly.first()
                CloudBackupScheduler.schedulePeriodicBackup(context, frequency, wifiOnly)
            }
        }
    }

    fun setCloudBackupWifiOnly(wifiOnly: Boolean) {
        if (realSessionId() == null) return
        viewModelScope.launch(Dispatchers.IO) {
            preferencesRepository.setCloudBackupWifiOnly(wifiOnly)
            _uiState.update { it.copy(cloudBackupWifiOnly = wifiOnly) }
            if (preferencesRepository.autoCloudBackupEnabled.first() &&
                AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            ) {
                val freq = preferencesRepository.cloudBackupFrequency.first()
                CloudBackupScheduler.schedulePeriodicBackup(context, freq, wifiOnly)
            }
        }
    }

    fun preparePendingAutoCloudBackupIfNeeded(forceImmediateTrigger: Boolean = false) {
        pendingAutoPrepareJob?.cancel()
        pendingAutoPrepareJob = viewModelScope.launch(Dispatchers.IO) {
            val session = sessionManager.getActiveSession() ?: return@launch
            if (session.slotId != VaultSessionManager.REAL_SLOT || session.isWiped) return@launch
            if (!preferencesRepository.autoCloudBackupEnabled.first()) return@launch
            val email = preferencesRepository.connectedGoogleAccount.first().trim()
            if (email.isBlank()) return@launch
            val recoveryPwd = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(context) ?: return@launch
            try {
                val items = repository.getAllItemsForBackupOrThrow(session.sessionId)
                CloudBackupScheduler.stageEncryptedSnapshot(context, email) { fos ->
                    repository.createAuthoritativeStreamingBackup(
                        items = items,
                        backupPasswordChars = recoveryPwd,
                        outputStream = fos,
                        expectedSessionId = session.sessionId
                    )
                }
                val wifiOnly = preferencesRepository.cloudBackupWifiOnly.first()
                val freq = preferencesRepository.cloudBackupFrequency.first()
                val lastCloudMs = preferencesRepository.lastCloudBackupEpochMs.first()
                val intervalMs = CloudBackupScheduler.intervalDaysFor(freq) * 24L * 60L * 60L * 1000L
                val isOverdue = forceImmediateTrigger || lastCloudMs == 0L || (System.currentTimeMillis() - lastCloudMs >= intervalMs)
                if (isOverdue) {
                    preferencesRepository.setAutoCloudBackupStatus("Backup ready — waiting for network")
                    CloudBackupScheduler.triggerImmediatePendingUpload(context, wifiOnly)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.tpeditz.privatevault.v2.data.CorruptedVaultRecordsException) {
                SafeVaultLogger.w("VaultViewModel", "Pending auto backup aborted: unreadable records")
                preferencesRepository.setAutoCloudBackupStatus("Some vault items could not be read — backup not created")
            } catch (e: Exception) {
                SafeVaultLogger.w("VaultViewModel", "Pending auto backup staging failed: ${e.message}")
            } finally {
                Arrays.fill(recoveryPwd, '\u0000')
            }
        }
    }

    fun uploadCloudBackup(
        backupPasswordChars: CharArray?,
        onConsentRequired: ((PendingIntent) -> Unit)? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val opId = cloudUploadOpSeq.incrementAndGet()
        activeCloudUploadOpId = opId
        fun completeCurrent(success: Boolean, message: String?) {
            deliverCurrentOperationResult(opId, activeCloudUploadOpId) { onComplete(success, message) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val tempBackupFile = File(context.cacheDir, "cloud_snapshot_${java.util.UUID.randomUUID()}.pvault")
            var resolvedChars: CharArray? = null
            var ownsResolvedChars = false
            var uploadCommitted = false
            try {
                val backupSessionId = requireRealSession()
                val activePwd = if (backupPasswordChars != null && backupPasswordChars.isNotEmpty()) {
                    val policy = PasswordPolicy.validateNewPassword(backupPasswordChars, "Cloud backup password")
                    if (!policy.isValid) {
                        return@launch withContext(Dispatchers.Main) {
                            completeCurrent(false, policy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters.")
                        }
                    }
                    resolvedChars = backupPasswordChars
                    backupPasswordChars
                } else {
                    val unwrapped = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(context)
                        ?: return@launch withContext(Dispatchers.Main) {
                            completeCurrent(false, "Enter a backup password or configure an Automatic Backup recovery password first.")
                        }
                    ownsResolvedChars = true
                    resolvedChars = unwrapped
                    unwrapped
                }

                val email = preferencesRepository.connectedGoogleAccount.first().trim()
                if (email.isBlank()) {
                    return@launch withContext(Dispatchers.Main) {
                        completeCurrent(false, "Connect a Google account first.")
                    }
                }

                var token = when (val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)) {
                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> authState.accessToken
                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                        withContext(Dispatchers.Main) {
                            deliverCurrentOperationResult(opId, activeCloudUploadOpId) { onConsentRequired?.invoke(authState.pendingIntent) }
                            completeCurrent(false, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                        return@launch withContext(Dispatchers.Main) {
                            completeCurrent(false, authState.userMessage)
                        }
                    }
                }

                val allItems = repository.getAllItemsForBackupOrThrow(backupSessionId)
                FileOutputStream(tempBackupFile).use { fos ->
                    repository.createAuthoritativeStreamingBackup(
                        items = allItems,
                        backupPasswordChars = activePwd,
                        outputStream = fos,
                        isCancelled = { !isActive || activeCloudUploadOpId != opId || realSessionId() != backupSessionId },
                        expectedSessionId = backupSessionId
                    )
                    fos.flush()
                    fos.fd.sync()
                }
                requireRealSession(backupSessionId)
                ensureActive()
                if (activeCloudUploadOpId != opId) throw CancellationException("Cloud backup superseded.")
                var res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                    if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                        token = refreshed.accessToken
                        requireRealSession(backupSessionId)
                        ensureActive()
                        if (activeCloudUploadOpId != opId) throw CancellationException("Cloud backup superseded.")
                        res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            deliverCurrentOperationResult(opId, activeCloudUploadOpId) { onConsentRequired?.invoke(refreshed.pendingIntent) }
                            completeCurrent(false, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                }
                val postSessionActive = realSessionId() == backupSessionId
                val currentEmail = preferencesRepository.connectedGoogleAccount.first().trim()
                if (activeCloudUploadOpId != opId) return@launch
                if (!postSessionActive || !currentEmail.equals(email, ignoreCase = true)) {
                    withContext(Dispatchers.Main) {
                        completeCurrent(false, "Vault session or connected Google account changed during cloud backup.")
                    }
                    return@launch
                }
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        uploadCommitted = true
                        runCatching { preferencesRepository.recordCloudBackup() }
                        runCatching { preferencesRepository.setAutoCloudBackupStatus("Backed up to Google Drive") }
                        runCatching { loadActiveVaultItems() }
                        withContext(Dispatchers.Main) { completeCurrent(true, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        val friendly = if (res.isAuthError) {
                            "Reconnect your Google account to continue."
                        } else {
                            res.message
                        }
                        preferencesRepository.setAutoCloudBackupStatus("Last upload failed: $friendly")
                        withContext(Dispatchers.Main) { completeCurrent(false, friendly) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.tpeditz.privatevault.v2.data.CorruptedVaultRecordsException) {
                val msg = VaultErrorMapper.mapBackupExportError(e)
                if (activeCloudUploadOpId == opId) preferencesRepository.setAutoCloudBackupStatus(msg)
                withContext(Dispatchers.Main) { completeCurrent(false, msg) }
            } catch (e: Exception) {
                if (uploadCommitted) {
                    withContext(Dispatchers.Main) { completeCurrent(true, null) }
                } else {
                    val msg = if (e is IllegalArgumentException || e is IllegalStateException) {
                        VaultErrorMapper.mapBackupExportError(e)
                    } else {
                        VaultErrorMapper.mapCloudError(e)
                    }
                    if (activeCloudUploadOpId == opId) preferencesRepository.setAutoCloudBackupStatus("Last upload failed: $msg")
                    withContext(Dispatchers.Main) { completeCurrent(false, msg) }
                }
            } finally {
                tempBackupFile.delete()
                if (ownsResolvedChars && resolvedChars != null) {
                    Arrays.fill(resolvedChars, '\u0000')
                }
                backupPasswordChars?.let { Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun downloadCloudSnapshot(
        fileId: String,
        onConsentRequired: ((PendingIntent) -> Unit)? = null,
        onDownloaded: (File?, String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = File(context.cacheDir, "cloud_download_${java.util.UUID.randomUUID()}.pvault")
            try {
                val downloadSessionId = requireRealSession()
                val email = preferencesRepository.connectedGoogleAccount.first().trim()
                if (email.isBlank()) {
                    return@launch withContext(Dispatchers.Main) {
                        onDownloaded(null, "Connect a Google account first.")
                    }
                }
                var token = when (val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)) {
                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> authState.accessToken
                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(authState.pendingIntent)
                            onDownloaded(null, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                        return@launch withContext(Dispatchers.Main) {
                            onDownloaded(null, authState.userMessage)
                        }
                    }
                }

                requireRealSession(downloadSessionId)
                var res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                    if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                        token = refreshed.accessToken
                        requireRealSession(downloadSessionId)
                        res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(refreshed.pendingIntent)
                            onDownloaded(null, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                }
                requireRealSession(downloadSessionId)
                val currentEmail = preferencesRepository.connectedGoogleAccount.first().trim()
                if (!currentEmail.equals(email, ignoreCase = true)) {
                    runCatching { targetFile.delete() }
                    withContext(Dispatchers.Main) {
                        onDownloaded(null, "Connected Google account changed during download.")
                    }
                    return@launch
                }
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        withContext(Dispatchers.Main) { onDownloaded(res.data, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        runCatching { targetFile.delete() }
                        withContext(Dispatchers.Main) { onDownloaded(null, res.message) }
                    }
                }
            } catch (e: CancellationException) {
                runCatching { targetFile.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { targetFile.delete() }
                withContext(Dispatchers.Main) { onDownloaded(null, VaultErrorMapper.mapCloudError(e)) }
            }
        }
    }

    fun queryCloudSnapshots(
        onConsentRequired: ((PendingIntent) -> Unit)? = null,
        onResult: (List<GoogleDriveSyncManager.CloudBackupMetadata>, String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val querySessionId = requireRealSession()
                val email = preferencesRepository.connectedGoogleAccount.first().trim()
                if (email.isBlank()) {
                    withContext(Dispatchers.Main) { onResult(emptyList(), "Connect a Google account first.") }
                    return@launch
                }
                var token = when (val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)) {
                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> authState.accessToken
                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(authState.pendingIntent)
                            onResult(emptyList(), "Approve Google Drive permission to list cloud backups.")
                        }
                        return@launch
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                        withContext(Dispatchers.Main) { onResult(emptyList(), authState.userMessage) }
                        return@launch
                    }
                }
                var res = googleDriveSyncManager.queryCloudBackups(token)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                    if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                        token = refreshed.accessToken
                        requireRealSession(querySessionId)
                        res = googleDriveSyncManager.queryCloudBackups(token)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(refreshed.pendingIntent)
                            onResult(emptyList(), "Approve Google Drive permission to list cloud backups.")
                        }
                        return@launch
                    }
                }
                requireRealSession(querySessionId)
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        withContext(Dispatchers.Main) { onResult(res.data, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        withContext(Dispatchers.Main) { onResult(emptyList(), res.message) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(emptyList(), VaultErrorMapper.mapCloudError(e)) }
            }
        }
    }

    fun deleteCloudSnapshot(fileId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val expectedId = requireRealSession()
                val email = preferencesRepository.connectedGoogleAccount.first().trim()
                if (email.isBlank()) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }
                val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                var token = (authState as? GoogleDriveAuthHelper.DriveAuthState.Authorized)?.accessToken
                if (token != null) {
                    requireRealSession(expectedId)
                    var res = googleDriveSyncManager.deleteCloudBackup(token, fileId)
                    if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                        val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                        if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                            token = refreshed.accessToken
                            requireRealSession(expectedId)
                            res = googleDriveSyncManager.deleteCloudBackup(token, fileId)
                        }
                    }
                    withContext(Dispatchers.Main) { onComplete(res is GoogleDriveSyncManager.SyncResult.Success) }
                } else {
                    withContext(Dispatchers.Main) { onComplete(false) }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    // ============================================================================================
    // Tools: Password Generator & Authenticator
    // ============================================================================================

    fun generatePassword(config: PasswordGeneratorEngine.GeneratorConfig): PasswordGeneratorEngine.GenerationResult {
        return PasswordGeneratorEngine.generate(config)
    }

    fun generatePassphrase(
        wordCount: Int = 4,
        separator: String = "-",
        capitalize: Boolean = true,
        includeNumber: Boolean = true
    ): String {
        return PasswordGeneratorEngine.generatePassphrase(wordCount, separator, capitalize, includeNumber)
    }

    fun saveAuthenticator(record: AuthenticatorRecord) {
        viewModelScope.launch {
            repository.saveItem(record.copy(updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
        }
    }

    fun deleteAuthenticator(id: String) {
        viewModelScope.launch {
            repository.deleteItem(id)
            loadActiveVaultItems()
        }
    }

    private fun startTotpTicker() {
        totpTickerJob?.cancel()
        totpTickerJob = viewModelScope.launch {
            while (isActive) {
                val epochSec = System.currentTimeMillis() / 1000L
                val rem = TotpEngine.getRemainingSeconds(epochSeconds = epochSec, periodSeconds = 30)
                _uiState.update { it.copy(totpRemainingSeconds = rem, totpEpochSeconds = epochSec) }
                delay(1000L)
            }
        }
    }

    // ============================================================================================
    // Local Portable Backup & Restore (Up to 2 GB)
    // ============================================================================================

    fun cancelBackup() {
        backupOpSeq.incrementAndGet()
        activeBackupOpId = 0L
        activeBackupJob?.cancel()
        activeBackupJob = null
        _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
    }

    fun cancelRestore() {
        restoreOpSeq.incrementAndGet()
        activeRestoreOpId = 0L
        activeRestoreJob?.cancel()
        activeRestoreJob = null
        clearPendingRestore()
        _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
    }

    fun dismissRestoreError() {
        _uiState.update { it.copy(pendingRestoreError = null) }
    }

    fun exportStreamingBackup(
        scope: BackupExportScope,
        passwordChars: CharArray,
        outputStream: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onCleanupIncomplete: () -> Boolean = { true },
        onComplete: (Boolean, String?) -> Unit
    ) {
        val pwdValidation = PasswordPolicy.validateNewPassword(passwordChars, "Backup password")
        if (!pwdValidation.isValid) {
            runCatching { outputStream.close() }
            cleanupIncompleteDocumentExport(exportCompleted = false, deleteIncompleteOutput = onCleanupIncomplete)
            val msg = pwdValidation.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters for your new backup password."
            _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null, activeErrorMessage = msg) }
            onComplete(false, msg)
            return
        }
        activeBackupJob?.cancel()
        val opId = backupOpSeq.incrementAndGet()
        activeBackupOpId = opId
        fun completeCurrent(success: Boolean, message: String?) {
            deliverCurrentOperationResult(opId, activeBackupOpId) { onComplete(success, message) }
        }
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Preparing backup...") }
        activeBackupJob = viewModelScope.launch(Dispatchers.IO) {
            var exportCompleted = false
            try {
                val expectedId = sessionManager.getCurrentSessionId() ?: error("Unlock your vault first.")
                val allItems = repository.getAllItemsForBackupOrThrow(expectedId)
                val filteredItems = filterItemsForBackupScope(allItems, scope)

                if (activeBackupOpId == opId) {
                    _uiState.update { it.copy(backupStatusMessage = "Encrypting and writing backup...") }
                }
                outputStream.use { os ->
                    repository.createAuthoritativeStreamingBackup(
                        items = filteredItems,
                        backupPasswordChars = passwordChars,
                        outputStream = os,
                        expectedSessionId = expectedId,
                        exportScope = scope,
                        isCancelled = { !isActive || activeBackupOpId != opId || sessionManager.getCurrentSessionId() != expectedId },
                        onProgress = { written, total ->
                            if (activeBackupOpId == opId && total > 0L) {
                                val ratio = (written.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                                val pct = (ratio * 100).toInt().coerceAtMost(99)
                                _uiState.update {
                                    it.copy(
                                        backupProgressRatio = ratio,
                                        backupStatusMessage = "Backing up... $pct%"
                                    )
                                }
                                onProgress(written, total)
                            }
                        }
                    )
                    ensureActive()
                    if (activeBackupOpId != opId || sessionManager.getCurrentSessionId() != expectedId) {
                        throw CancellationException("Backup superseded or session changed.")
                    }
                    finalizeBackupOutput(os) { fileStream ->
                        val stat = android.system.Os.fstat(fileStream.fd)
                        if (android.system.OsConstants.S_ISREG(stat.st_mode)) {
                            fileStream.fd.sync()
                        }
                        // Pipe-backed SAF providers finalize via successful flush and close.
                    }
                }
                exportCompleted = true
                runCatching { preferencesRepository.recordLocalBackup() }
                withContext(Dispatchers.Main) {
                    if (activeBackupOpId == opId) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                backupProgressRatio = 1f,
                                backupStatusMessage = "Backup complete",
                                activeSnackbarMessage = "Backup saved successfully."
                            )
                        }
                    }
                    completeCurrent(true, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Backup", "Backup cancelled by user")
                val cleaned = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    cleanupIncompleteDocumentExport(exportCompleted, onCleanupIncomplete)
                }
                if (exportCompleted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeBackupOpId == opId) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    backupProgressRatio = 1f,
                                    backupStatusMessage = "Backup complete",
                                    activeSnackbarMessage = "Backup saved successfully."
                                )
                            }
                        }
                        completeCurrent(true, null)
                    }
                } else {
                    val msg = if (cleaned == false) {
                        "Backup cancelled. An incomplete file may remain at the chosen location."
                    } else {
                        "Backup cancelled."
                    }
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeBackupOpId == opId) {
                            _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                        }
                        completeCurrent(false, msg)
                    }
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Backup", "Export failed", e)
                if (exportCompleted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeBackupOpId == opId) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    backupProgressRatio = 1f,
                                    backupStatusMessage = "Backup complete",
                                    activeSnackbarMessage = "Backup saved successfully."
                                )
                            }
                        }
                        completeCurrent(true, null)
                    }
                } else {
                    val cleaned = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        cleanupIncompleteDocumentExport(exportCompleted = false, deleteIncompleteOutput = onCleanupIncomplete)
                    }
                    val baseMsg = VaultErrorMapper.mapBackupExportError(e)
                    val friendly = if (cleaned == false) {
                        "$baseMsg An incomplete file may remain at the chosen location."
                    } else {
                        baseMsg
                    }
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeBackupOpId == opId) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    backupProgressRatio = null,
                                    backupStatusMessage = null,
                                    activeErrorMessage = friendly
                                )
                            }
                        }
                        completeCurrent(false, friendly)
                    }
                }
            } finally {
                if (activeBackupOpId == opId) {
                    activeBackupJob = null
                }
                runCatching { outputStream.close() }
                Arrays.fill(passwordChars, '\u0000')
            }
        }
    }

    fun inspectBackupForRestore(
        inputStream: InputStream,
        passwordChars: CharArray,
        onResult: (BackupPayload?, String?) -> Unit
    ) {
        activeRestoreJob?.cancel()
        val opId = restoreOpSeq.incrementAndGet()
        activeRestoreOpId = opId
        fun resultCurrent(payload: BackupPayload?, message: String?) {
            deliverCurrentOperationResult(opId, activeRestoreOpId) { onResult(payload, message) }
        }
        _uiState.update {
            it.copy(
                isLoading = true,
                backupProgressRatio = null,
                backupStatusMessage = "Reading and verifying backup...",
                pendingRestoreError = null,
                pendingRestorePayload = null
            )
        }
        val initialSessionId = sessionManager.getCurrentSessionId()
        val stagingDir = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val bis = BufferedInputStream(inputStream)
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                val hadInitializedVault = repository.isVaultInitialized()
                if (hadInitializedVault && initialSessionId == null) {
                    throw IllegalStateException("Vault is locked. Unlock before inspecting a backup.")
                }
                val headerBuf = ByteArray(8)
                bis.mark(16)
                var headerBytesRead = 0
                while (headerBytesRead < headerBuf.size) {
                    val r = bis.read(headerBuf, headerBytesRead, headerBuf.size - headerBytesRead)
                    if (r <= 0) break
                    headerBytesRead += r
                }
                bis.reset()
                if (headerBytesRead < 8) {
                    throw IllegalArgumentException("Invalid backup magic header.")
                }

                val isCancelledCheck = {
                    !isActive || activeRestoreOpId != opId || (hadInitializedVault && sessionManager.getCurrentSessionId() != initialSessionId)
                }
                val progressCallback: (Long, Long) -> Unit = { readBytes, totalBytes ->
                    if (activeRestoreOpId == opId && totalBytes > 0L) {
                        val ratio = (readBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                        val pct = (ratio * 100).toInt().coerceAtMost(99)
                        _uiState.update {
                            it.copy(
                                backupProgressRatio = ratio,
                                backupStatusMessage = "Verifying backup... $pct%"
                            )
                        }
                    }
                }

                val staged = if (EncryptedBackupV2Engine.isSupportedBackup(headerBuf)) {
                    EncryptedBackupV2Engine.inspectAndStageBackup(
                        inputStream = bis,
                        backupPasswordChars = passwordChars,
                        stagingDir = stagingDir,
                        isCancelled = isCancelledCheck,
                        onProgress = progressCallback
                    )
                } else if (LegacyV1BackupReader.isLegacyV1Backup(headerBuf)) {
                    LegacyV1BackupReader.inspectAndStageLegacyBackup(
                        inputStream = bis,
                        backupPasswordChars = passwordChars,
                        stagingDir = stagingDir,
                        isCancelled = isCancelledCheck,
                        onProgress = progressCallback
                    )
                } else {
                    throw IllegalArgumentException("Invalid backup magic header.")
                }
                ensureActive()
                if (activeRestoreOpId != opId) {
                    stagingDir.deleteRecursively()
                    throw CancellationException("Restore inspection superseded.")
                }
                if (hadInitializedVault && sessionManager.getCurrentSessionId() != initialSessionId) {
                    throw IllegalStateException("Session changed before backup staging completed.")
                }

                withContext(Dispatchers.Main) {
                    if (activeRestoreOpId == opId) {
                        currentStagedRestore?.stagingDir?.takeIf { it.absolutePath != staged.stagingDir.absolutePath }?.deleteRecursively()
                        currentStagedRestore = staged
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                backupProgressRatio = null,
                                backupStatusMessage = null,
                                pendingRestorePayload = staged.payload,
                                pendingRestoreError = null
                            )
                        }
                    } else {
                        stagingDir.deleteRecursively()
                    }
                    resultCurrent(staged.payload, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Restore inspection cancelled")
                stagingDir.deleteRecursively()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (activeRestoreOpId == opId) {
                        _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                    }
                    resultCurrent(null, "Restore cancelled")
                }
            } catch (e: Exception) {
                SafeVaultLogger.w("Restore", "Failed to decrypt backup: ${e.message}")
                stagingDir.deleteRecursively()
                val friendly = VaultErrorMapper.mapRestoreError(e)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (activeRestoreOpId == opId) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                backupProgressRatio = null,
                                backupStatusMessage = null,
                                pendingRestoreError = friendly
                            )
                        }
                    }
                    resultCurrent(null, friendly.message)
                }
            } finally {
                if (activeRestoreOpId == opId) {
                    activeRestoreJob = null
                }
                runCatching { bis.close() }
                Arrays.fill(passwordChars, '\u0000')
            }
        }
    }

    fun confirmRestore(mode: RestoreMode, onComplete: (Boolean) -> Unit) {
        val expectedSessionId = sessionManager.getCurrentSessionId() ?: run {
            _uiState.update {
                it.copy(pendingRestoreError = VaultErrorMapper.mapRestoreError(IllegalStateException("Vault is locked.")))
            }
            onComplete(false)
            return
        }
        val staged = currentStagedRestore
        if (staged == null || !staged.stagingDir.isDirectory || _uiState.value.pendingRestorePayload == null) {
            clearPendingRestore()
            _uiState.update { it.copy(pendingRestoreError = restoreReselectError()) }
            onComplete(false)
            return
        }
        if (mode == RestoreMode.REPLACE_ACTIVE_VAULT && staged.payload.isPartialCategoryBackup) {
            val friendly = VaultErrorMapper.mapRestoreError(
                IllegalArgumentException(
                    "Replace Active Vault is not allowed for partial category backups (${staged.payload.scopeDisplayLabel})."
                )
            )
            _uiState.update { it.copy(pendingRestoreError = friendly) }
            onComplete(false)
            return
        }
        activeRestoreJob?.cancel()
        val opId = restoreOpSeq.incrementAndGet()
        activeRestoreOpId = opId
        fun completeCurrent(success: Boolean) {
            deliverCurrentOperationResult(opId, activeRestoreOpId) { onComplete(success) }
        }
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Restoring vault items...", pendingRestoreError = null) }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            var committed = false
            try {
                ensureActive()
                if (activeRestoreOpId != opId || sessionManager.getCurrentSessionId() != expectedSessionId) {
                    throw IllegalStateException("Session changed during restore.")
                }
                repository.commitRestore(
                    staged = staged,
                    mode = mode,
                    isCancelled = { !isActive || activeRestoreOpId != opId || sessionManager.getCurrentSessionId() != expectedSessionId },
                    allowUnknownScopeReplace = true
                )
                committed = true
                if (activeRestoreOpId == opId) {
                    currentStagedRestore = null
                }
                runCatching { loadActiveVaultItems() }
                withContext(Dispatchers.Main) {
                    if (activeRestoreOpId == opId) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                backupProgressRatio = null,
                                backupStatusMessage = null,
                                pendingRestorePayload = null,
                                pendingRestoreError = null,
                                activeSnackbarMessage = "Vault restored successfully"
                            )
                        }
                    }
                    completeCurrent(true)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Restore commit cancelled")
                if (committed) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        completeCurrent(true)
                    }
                } else {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeRestoreOpId == opId) {
                            invalidateMissingRestoreStage(staged)
                            _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                        }
                        completeCurrent(false)
                    }
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Restore", "Restore commit failed", e)
                if (committed) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        completeCurrent(true)
                    }
                } else {
                    val friendly = VaultErrorMapper.mapRestoreError(e)
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeRestoreOpId == opId) {
                            val stageLost = invalidateMissingRestoreStage(staged)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    backupProgressRatio = null,
                                    backupStatusMessage = null,
                                    pendingRestoreError = if (stageLost) restoreReselectError() else friendly
                                )
                            }
                        }
                        completeCurrent(false)
                    }
                }
            } finally {
                if (activeRestoreOpId == opId) {
                    activeRestoreJob = null
                }
            }
        }
    }

    fun createVaultAndCommitRestore(
        masterPasswordChars: CharArray,
        mode: RestoreMode? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val staged = currentStagedRestore
        if (staged == null || !staged.stagingDir.isDirectory || _uiState.value.pendingRestorePayload == null) {
            clearPendingRestore()
            _uiState.update { it.copy(pendingRestoreError = restoreReselectError()) }
            Arrays.fill(masterPasswordChars, '\u0000')
            onComplete(false, restoreReselectError().message)
            return
        }
        val pwdPolicy = PasswordPolicy.validateNewPassword(masterPasswordChars, "Master password")
        if (!pwdPolicy.isValid) {
            onComplete(false, pwdPolicy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters for your new master password.")
            return
        }
        val effectiveMode = mode ?: if (staged.payload.isVerifiedFullBackup) {
            RestoreMode.REPLACE_ACTIVE_VAULT
        } else {
            RestoreMode.MERGE_KEEP_NEWEST
        }
        activeRestoreJob?.cancel()
        val opId = restoreOpSeq.incrementAndGet()
        activeRestoreOpId = opId
        fun completeCurrent(success: Boolean, message: String?) {
            deliverCurrentOperationResult(opId, activeRestoreOpId) { onComplete(success, message) }
        }
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Initializing vault and restoring data...", pendingRestoreError = null) }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            var vaultAndRestoreCommitted = false
            try {
                ensureActive()
                val res = repository.initializeVaultAndCommitRestore(
                    masterPasswordChars = masterPasswordChars,
                    staged = staged,
                    mode = effectiveMode,
                    isCancelled = { !isActive || activeRestoreOpId != opId }
                )
                if (res !is AuthResult.Success) {
                    val errMsg = (res as? AuthResult.Error)?.message ?: "Failed to initialize vault."
                    withContext(Dispatchers.Main) {
                        if (activeRestoreOpId == opId) {
                            _uiState.update { it.copy(isLoading = false, backupStatusMessage = null) }
                        }
                        completeCurrent(false, errMsg)
                    }
                    return@launch
                }
                vaultAndRestoreCommitted = true
                if (activeRestoreOpId == opId) {
                    currentStagedRestore = null
                }
                runCatching { loadActiveVaultItems() }
                val isBiometricAvailable = BiometricKeystoreManager.hasKeystoreKey()
                withContext(Dispatchers.Main) {
                    if (activeRestoreOpId == opId) {
                        _uiState.update {
                            it.copy(
                                isVaultInitialized = true,
                                isUnlocked = true,
                                activeSlotId = 0,
                                pendingRestorePayload = null,
                                pendingRestoreError = null,
                                isLoading = false,
                                backupProgressRatio = null,
                                backupStatusMessage = null,
                                isBiometricsConfigured = isBiometricAvailable,
                                activeSnackbarMessage = "Vault initialized and restored successfully"
                            )
                        }
                    }
                    completeCurrent(true, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Create vault and restore cancelled")
                if (vaultAndRestoreCommitted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        completeCurrent(true, null)
                    }
                } else {
                    val stillInitialized = runCatching { repository.isVaultInitialized() }.getOrDefault(false)
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeRestoreOpId == opId) {
                            invalidateMissingRestoreStage(staged)
                            _uiState.update {
                                it.copy(
                                    isVaultInitialized = stillInitialized,
                                    isLoading = false,
                                    backupProgressRatio = null,
                                    backupStatusMessage = null
                                )
                            }
                        }
                        completeCurrent(false, if (!staged.stagingDir.isDirectory) restoreReselectError().message else "Restore cancelled")
                    }
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Restore", "Create vault and restore failed", e)
                if (vaultAndRestoreCommitted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        completeCurrent(true, null)
                    }
                } else {
                    val friendly = VaultErrorMapper.mapRestoreError(e)
                    val stillInitialized = runCatching { repository.isVaultInitialized() }.getOrDefault(false)
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        if (activeRestoreOpId == opId) {
                            invalidateMissingRestoreStage(staged)
                            _uiState.update {
                                it.copy(
                                    isVaultInitialized = stillInitialized,
                                    isLoading = false,
                                    backupProgressRatio = null,
                                    backupStatusMessage = null,
                                    pendingRestoreError = if (!staged.stagingDir.isDirectory) restoreReselectError() else friendly
                                )
                            }
                        }
                        completeCurrent(false, if (!staged.stagingDir.isDirectory) restoreReselectError().message else friendly.message)
                    }
                }
            } finally {
                Arrays.fill(masterPasswordChars, '\u0000')
                if (activeRestoreOpId == opId) {
                    activeRestoreJob = null
                }
            }
        }
    }

    private fun restoreReselectError() = VaultErrorMapper.FriendlyRestoreError(
        kind = VaultErrorMapper.RestoreErrorKind.FILE_UNACCESSIBLE,
        title = "Choose your backup again",
        message = "The temporary restore files are no longer available. Select your original backup again to retry.",
        canChooseAnotherFile = true
    )

    private fun invalidateMissingRestoreStage(staged: StagedBackupRestore): Boolean {
        if (staged.stagingDir.isDirectory) return false
        if (currentStagedRestore === staged) {
            currentStagedRestore = null
            _uiState.update {
                it.copy(pendingRestorePayload = null, pendingRestoreError = restoreReselectError())
            }
        }
        return true
    }

    fun clearPendingRestore() {
        currentStagedRestore?.stagingDir?.deleteRecursively()
        currentStagedRestore = null
        _uiState.update {
            it.copy(
                pendingRestorePayload = null,
                pendingRestoreError = null,
                backupProgressRatio = null,
                backupStatusMessage = null
            )
        }
    }

    fun changeMasterPassword(
        currentPasswordChars: CharArray,
        newPasswordChars: CharArray,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val policy = PasswordPolicy.validateNewPassword(newPasswordChars, "Master password")
        if (!policy.isValid) {
            onComplete(false, policy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = repository.changeMasterPassword(currentPasswordChars, newPasswordChars)
                if (success) {
                    preferencesRepository.clearBiometricPreferences()
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        _uiState.update {
                            it.copy(
                                isBiometricsConfigured = false,
                                privacyMetrics = it.privacyMetrics.copy(isBiometricEnabled = false)
                            )
                        }
                        onComplete(true, null)
                    } else {
                        onComplete(false, "Current password is incorrect.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, VaultErrorMapper.mapVaultActionError(e)) }
            }
        }
    }

    private fun recomputeControlCenterPolicyUi() {
        val snapshot = _policySnapshot.value
        val now = System.currentTimeMillis()
        val flags = PolicyEvaluator.effectiveFeatureFlags(snapshot, now)
        val betaOptIn = _uiState.value.betaChannelOptIn
        val effectiveAnnouncements = PolicyEvaluator.selectEffectiveAnnouncements(
            snapshot = snapshot,
            currentVersionCode = BuildConfig.VERSION_CODE,
            betaChannelOptIn = betaOptIn,
            dismissedOnceIds = lastDismissedOnceAnnouncementIds,
            sessionDismissedRepeatIds = _sessionDismissedRepeatAnnouncementIds.value,
            nowEpochMs = now
        )
        val banners = effectiveAnnouncements.filter { it.presentation == AnnouncementPresentation.BANNER }
        val activeDialog = effectiveAnnouncements.firstOrNull { it.presentation == AnnouncementPresentation.DIALOG }
        val feedbackState = PolicyFeedbackState(
            isSupportedByBuild = BuildConfig.FEEDBACK_SUBMISSION_ENABLED,
            isEnabledByServer = flags.feedbackEnabled,
            isMaintenanceActive = flags.onlineMaintenance
        )
        _uiState.update {
            it.copy(
                betaEnrollmentAllowed = flags.betaEnrollmentEnabled,
                onlineMaintenanceActive = flags.onlineMaintenance,
                otaCheckIntervalHours = flags.updateCheckIntervalHours,
                activeAnnouncements = banners,
                activeDialogAnnouncement = activeDialog,
                feedbackState = feedbackState
            )
        }
    }

    fun setBetaChannelOptIn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBetaChannelOptIn(enabled)
            if (enabled) {
                otaUpdateManager.syncPolicySnapshot(forceRefresh = true)
                    ?.anySnapshotForDisplay
                    ?.let { _policySnapshot.value = it }
                recomputeControlCenterPolicyUi()
            }
        }
    }

    fun dismissAnnouncement(announcement: AnnouncementRecord) {
        viewModelScope.launch {
            when (announcement.repeatMode) {
                AnnouncementRepeatMode.ONCE -> {
                    preferencesRepository.dismissAnnouncementId(announcement.id)
                }

                AnnouncementRepeatMode.REPEAT -> {
                    _sessionDismissedRepeatAnnouncementIds.value =
                        _sessionDismissedRepeatAnnouncementIds.value + announcement.id
                    recomputeControlCenterPolicyUi()
                }
            }
        }
    }

    fun submitFeedback(
        category: String,
        message: String,
        contact: String?,
        consentChecked: Boolean,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (submitFeedbackJob?.isActive == true) return
        val state = _uiState.value.feedbackState
        if (!state.isSubmissionAllowed) {
            onComplete(
                false,
                state.disabledReason ?: "Feedback submission is disabled pending server endpoint deployment."
            )
            return
        }
        submitFeedbackJob = viewModelScope.launch {
            when (val res = feedbackClient.submitFeedback(category, message, contact, consentChecked)) {
                is SupabaseFeedbackClient.SubmitResult.Accepted -> {
                    onComplete(true, "Saved successfully. Thank you for your feedback.")
                }

                is SupabaseFeedbackClient.SubmitResult.Rejected -> {
                    onComplete(false, res.message)
                }

                is SupabaseFeedbackClient.SubmitResult.NetworkError -> {
                    onComplete(false, res.message)
                }
            }
        }
    }

    fun notifyFeedbackStatus(message: String) {
        _uiState.update { it.copy(activeSnackbarMessage = message) }
    }

    /**
     * Called on activity resume, result from Android Settings, and vault unlock.
     * Recover the current download before considering a new network request.
     */
    fun refreshOtaOnForeground() {
        if (!otaForeground || foregroundOtaJob?.isActive == true) return
        foregroundOtaJob = viewModelScope.launch {
            otaUpdateManager.recheckInstallPermissionAndVerifyCandidate()
            if (!otaForeground || !_uiState.value.isUnlocked) return@launch

            val syncResult = otaUpdateManager.syncPolicySnapshot(forceRefresh = false)
            val displaySnapshot = syncResult?.anySnapshotForDisplay
            if (displaySnapshot != null) {
                _policySnapshot.value = displaySnapshot
                if (syncResult is PolicySyncResult.Fresh) {
                    val currentInterval = preferencesRepository.effectiveOtaCheckIntervalHours.first()
                    val targetInterval = displaySnapshot.featureFlags.updateCheckIntervalHours
                    if (targetInterval != currentInterval) {
                        preferencesRepository.setEffectiveOtaCheckIntervalHours(targetInterval)
                        if (preferencesRepository.automaticOtaCheckEnabled.first()) {
                            OtaPolicyScheduler.schedulePeriodicPolicyCheck(context, targetInterval)
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) return@launch
            if (!preferencesRepository.automaticOtaCheckEnabled.first()) return@launch
            val effectiveFlags = PolicyEvaluator.effectiveFeatureFlags(_policySnapshot.value, System.currentTimeMillis())
            if (effectiveFlags.onlineMaintenance) return@launch
            val current = otaUpdateManager.otaState.value
            if (current.isPendingInstallation || current is OtaState.Downloading || current is OtaState.Verifying || current is OtaState.Checking) {
                return@launch
            }
            val now = SystemClock.elapsedRealtime()
            val last = lastAutomaticOtaAttemptElapsed
            if (last != null && now - last < 15 * 60 * 1000L) return@launch
            lastAutomaticOtaAttemptElapsed = now
            val result = otaUpdateManager.checkForUpdates(isUserInitiated = false)
            otaUpdateManager.currentCachedPolicySnapshot()?.let { _policySnapshot.value = it }
            if (result is OtaState.UpdateAvailable || result is OtaState.UpToDate) {
                runCatching { preferencesRepository.setLastOtaCheckEpochMs() }
            }
        }
    }

    fun setAutomaticOtaCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutomaticOtaCheckEnabled(enabled)
            if (enabled) {
                val interval = preferencesRepository.effectiveOtaCheckIntervalHours.first()
                OtaPolicyScheduler.schedulePeriodicPolicyCheck(context, interval)
                lastAutomaticOtaAttemptElapsed = null
                refreshOtaOnForeground()
            } else {
                OtaPolicyScheduler.cancelPeriodicPolicyCheck(context)
                otaUpdateManager.dismissUpdatePrompt()
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            val recovered = otaUpdateManager.recheckInstallPermissionAndVerifyCandidate()
            if (recovered.isPendingInstallation) return@launch
            val result = otaUpdateManager.checkForUpdates(isUserInitiated = manual)
            _policySnapshot.value = otaUpdateManager.currentCachedPolicySnapshot()
            if (result is OtaState.UpdateAvailable || result is OtaState.UpToDate) {
                lastAutomaticOtaAttemptElapsed = SystemClock.elapsedRealtime()
                runCatching { preferencesRepository.setLastOtaCheckEpochMs() }
            }
        }
    }

    fun dismissUpdateReminder() {
        otaUpdateManager.dismissUpdatePrompt()
        _showPendingUpdateReminder.value = false
    }

    fun rearmPendingUpdateReminder() {
        _showPendingUpdateReminder.value = true
    }

    fun startUpdateDownload(manifest: com.tpeditz.privatevault.v2.ota.UpdateManifest) {
        _showPendingUpdateReminder.value = true
        viewModelScope.launch {
            otaUpdateManager.startDownload(manifest)
        }
    }

    fun cancelUpdateDownload() {
        otaUpdateManager.cancelActiveDownload()
    }

    fun launchPackageInstaller(apkFile: File) {
        if (installUpdateJob?.isActive == true) return
        installUpdateJob = viewModelScope.launch {
            otaUpdateManager.launchPackageInstaller(apkFile)
        }
    }

    // ============================================================================================
    // Search & Filters
    // ============================================================================================

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedFolder(folderId: String?) {
        _uiState.update { it.copy(selectedFolderId = folderId) }
    }

    fun setFilterFavoritesOnly(enabled: Boolean) {
        _uiState.update { it.copy(filterFavoritesOnly = enabled) }
    }

    fun setFilterArchivedOnly(enabled: Boolean) {
        _uiState.update { it.copy(filterArchivedOnly = enabled) }
    }

    fun setFilterTrashOnly(enabled: Boolean) {
        _uiState.update { it.copy(filterTrashOnly = enabled) }
    }

    override fun onCleared() {
        super.onCleared()
        clearVolatileThumbnailCache()
        cancelBackup()
        cancelRestore()
    }

    // Factory
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as? com.tpeditz.privatevault.v2.PrivateVaultApplication
                ?: com.tpeditz.privatevault.v2.PrivateVaultApplication.instance
            return VaultViewModel(
                context = app,
                repository = app.repository,
                sessionManager = app.sessionManager,
                preferencesRepository = app.preferencesRepository,
                clipboardManager = app.clipboardManager,
                otaUpdateManager = app.otaUpdateManager,
                googleDriveSyncManager = app.googleDriveSyncManager
            ) as T
        }
    }
}
