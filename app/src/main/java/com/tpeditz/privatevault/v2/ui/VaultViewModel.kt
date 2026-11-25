package com.tpeditz.privatevault.v2.ui

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import com.tpeditz.privatevault.v2.BuildConfig
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tpeditz.privatevault.v2.autofill.AutofillHelper
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
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.ota.isPendingInstallation
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
    private var activeRestoreJob: Job? = null
    private var foregroundOtaJob: Job? = null
    private var installUpdateJob: Job? = null
    private var lastAutomaticOtaAttemptElapsed: Long? = null
    private var otaForeground = false
    private val _showPendingUpdateReminder = MutableStateFlow(true)
    val showPendingUpdateReminder: StateFlow<Boolean> = _showPendingUpdateReminder.asStateFlow()


    init {
        // Collect session state
        var lastActiveSessionId: Long? = null
        viewModelScope.launch {
            sessionManager.sessionState.collect { session ->
                when (session) {
                    is VaultSessionManager.SessionState.Unlocked -> {
                        if (lastActiveSessionId != session.sessionId) {
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
        viewModelScope.launch {
            if (preferencesRepository.lockOnScreenOff.first()) {
                lockVault()
            }
        }
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

    suspend fun enrollBiometrics(cipher: Cipher): Boolean {
        val ok = repository.enrollBiometricKey(cipher)
        if (ok) {
            preferencesRepository.setBiometricUnlockEnabled(true)
            _uiState.update { it.copy(isBiometricsConfigured = true) }
        }
        return ok
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
            val liveAutofill = AutofillHelper.isAutofillServiceEnabled(context)
            runCatching { preferencesRepository.setAutofillEnabled(liveAutofill) }

            val currentOta = otaUpdateManager.otaState.value
            val updateAvailable = currentOta is OtaState.UpdateAvailable ||
                currentOta is OtaState.ReadyToInstall ||
                currentOta is OtaState.NeedsUnknownSourcesPermission

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
                isAutofillActive = liveAutofill,
                isUpdateAvailable = updateAvailable
            )

            ensureActive()
            sessionManager.runIfCurrentSession(expectedSessionId) {
              _uiState.update {
                it.copy(
                    isBiometricsConfigured = bio,
                    autoCloudBackupKeyConfigured = real && AutoCloudBackupKeyStore.hasRecoveryPassword(context),
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

    fun saveNote(note: NoteRecord) {
        viewModelScope.launch {
            repository.saveItem(note.copy(updatedAtEpochMs = System.currentTimeMillis()))
            loadActiveVaultItems()
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

    fun importCsv(inputStream: InputStream, onComplete: (Int, Int) -> Unit) {
        viewModelScope.launch {
            val res = repository.importCredentialsFromCsv(inputStream)
            loadActiveVaultItems()
            onComplete(res.first, res.second)
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

    suspend fun openEncryptedVideoDataSource(mediaRecord: MediaRecord): StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource {
        return repository.openEncryptedVideoDataSource(mediaRecord)
    }

    suspend fun loadDecryptedDocumentBytes(documentRecord: DocumentRecord): ByteArray {
        return repository.loadDecryptedDocumentBytes(documentRecord)
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
            val liveAutofill = AutofillHelper.isAutofillServiceEnabled(context)
            runCatching { preferencesRepository.setAutofillEnabled(liveAutofill) }
            val bioPref = preferencesRepository.biometricUnlockEnabled.first()
            val actualBio = bioPref && BiometricKeystoreManager.hasKeystoreKey() &&
                sessionManager.getActiveSlotIdOrNull() != VaultSessionManager.DECOY_SLOT
            val storedEmail = preferencesRepository.connectedGoogleAccount.first().trim()
            val hasRecoveryPwd = AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            val autoEnabled = preferencesRepository.autoCloudBackupEnabled.first()
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
                    privacyMetrics = it.privacyMetrics.copy(
                        isAutofillActive = liveAutofill,
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
            preferencesRepository.setBiometricUnlockEnabled(false)
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
                val items = repository.getAllItemsForActiveSlot(session.sessionId)
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
        viewModelScope.launch(Dispatchers.IO) {
            val tempBackupFile = File(context.cacheDir, "cloud_snapshot_${System.currentTimeMillis()}.pvault")
            var resolvedChars: CharArray? = null
            var ownsResolvedChars = false
            try {
                val backupSessionId = requireRealSession()
                val activePwd = if (backupPasswordChars != null && backupPasswordChars.isNotEmpty()) {
                    val policy = PasswordPolicy.validateNewPassword(backupPasswordChars, "Cloud backup password")
                    if (!policy.isValid) {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, policy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters.")
                        }
                    }
                    resolvedChars = backupPasswordChars
                    backupPasswordChars
                } else {
                    val unwrapped = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(context)
                        ?: return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Enter a backup password or configure an Automatic Backup recovery password first.")
                        }
                    ownsResolvedChars = true
                    resolvedChars = unwrapped
                    unwrapped
                }

                val email = preferencesRepository.connectedGoogleAccount.first().trim()
                if (email.isBlank()) {
                    return@launch withContext(Dispatchers.Main) {
                        onComplete(false, "Connect a Google account first.")
                    }
                }

                var token = when (val authState = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)) {
                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> authState.accessToken
                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(authState.pendingIntent)
                            onComplete(false, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, authState.userMessage)
                        }
                    }
                }

                val allItems = repository.getAllItemsForActiveSlot(backupSessionId)
                FileOutputStream(tempBackupFile).use { fos ->
                    repository.createAuthoritativeStreamingBackup(
                        items = allItems,
                        backupPasswordChars = activePwd,
                        outputStream = fos,
                        expectedSessionId = backupSessionId
                    )
                    fos.flush()
                    fos.fd.sync()
                }
                requireRealSession(backupSessionId)
                var res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                    if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                        token = refreshed.accessToken
                        requireRealSession(backupSessionId)
                        res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(refreshed.pendingIntent)
                            onComplete(false, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
                }
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        preferencesRepository.recordCloudBackup()
                        preferencesRepository.setAutoCloudBackupStatus("Backed up to Google Drive")
                        loadActiveVaultItems()
                        withContext(Dispatchers.Main) { onComplete(true, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        val friendly = if (res.isAuthError) {
                            "Reconnect your Google account to continue."
                        } else {
                            res.message
                        }
                        preferencesRepository.setAutoCloudBackupStatus("Last upload failed: $friendly")
                        withContext(Dispatchers.Main) { onComplete(false, friendly) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, VaultErrorMapper.mapCloudError(e)) }
            } finally {
                tempBackupFile.delete()
                if (ownsResolvedChars && resolvedChars != null) {
                    Arrays.fill(resolvedChars, '\u0000')
                }
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
                requireRealSession()
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

                var res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.requestOrFetchDriveAccessToken(context, email)
                    if (refreshed is GoogleDriveAuthHelper.DriveAuthState.Authorized) {
                        token = refreshed.accessToken
                        res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(refreshed.pendingIntent)
                            onDownloaded(null, "Approve Google Drive permission and try again.")
                        }
                        return@launch
                    }
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
                requireRealSession()
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
                        res = googleDriveSyncManager.queryCloudBackups(token)
                    } else if (refreshed is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent) {
                        withContext(Dispatchers.Main) {
                            onConsentRequired?.invoke(refreshed.pendingIntent)
                            onResult(emptyList(), "Approve Google Drive permission to list cloud backups.")
                        }
                        return@launch
                    }
                }
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        withContext(Dispatchers.Main) { onResult(res.data, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        withContext(Dispatchers.Main) { onResult(emptyList(), res.message) }
                    }
                }
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
                val rem = TotpEngine.getRemainingSeconds()
                _uiState.update { it.copy(totpRemainingSeconds = rem) }
                delay(1000L)
            }
        }
    }

    // ============================================================================================
    // Local Portable Backup & Restore (Up to 2 GB)
    // ============================================================================================

    fun cancelBackup() {
        activeBackupJob?.cancel()
        activeBackupJob = null
        _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
    }

    fun cancelRestore() {
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
        onComplete: (Boolean, String?) -> Unit
    ) {
        val pwdValidation = PasswordPolicy.validateNewPassword(passwordChars, "Backup password")
        if (!pwdValidation.isValid) {
            runCatching { outputStream.close() }
            val msg = pwdValidation.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters for your new backup password."
            _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null, activeErrorMessage = msg) }
            onComplete(false, msg)
            return
        }
        activeBackupJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = 0f, backupStatusMessage = "Preparing backup...") }
        activeBackupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val expectedId = sessionManager.getCurrentSessionId() ?: error("Unlock your vault first.")
                val allItems = repository.getAllItemsForActiveSlot(expectedId)
                val filteredItems = when (scope) {
                    BackupExportScope.ALL_DATA -> allItems
                    BackupExportScope.NOTES_PASSWORDS_LINKS -> allItems.filter {
                        it is NoteRecord || it is CredentialRecord || it is SecureLinkRecord || it is VaultFolder
                    }
                    BackupExportScope.NOTES_ONLY -> allItems.filter { it is NoteRecord || it is VaultFolder }
                    BackupExportScope.PASSWORDS_ONLY -> allItems.filter { it is CredentialRecord || it is VaultFolder }
                    BackupExportScope.MEDIA_ONLY -> allItems.filter { it is MediaRecord || it is VaultAlbumRecord }
                    BackupExportScope.DOCUMENTS_ONLY -> allItems.filter { it is DocumentRecord || it is VaultFolder }
                }

                _uiState.update { it.copy(backupStatusMessage = "Encrypting and writing backup...") }
                repository.createAuthoritativeStreamingBackup(
                    items = filteredItems,
                    backupPasswordChars = passwordChars,
                    outputStream = outputStream,
                    expectedSessionId = expectedId,
                    isCancelled = { !isActive },
                    onProgress = { written, total ->
                        val ratio = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        _uiState.update {
                            it.copy(
                                backupProgressRatio = ratio,
                                backupStatusMessage = "Backing up... ${(ratio * 100).toInt()}%"
                            )
                        }
                        onProgress(written, total)
                    }
                )
                preferencesRepository.recordLocalBackup()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = 1f,
                            backupStatusMessage = "Backup complete",
                            activeSnackbarMessage = "Backup exported successfully"
                        )
                    }
                    onComplete(true, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Backup", "Backup cancelled by user")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                    onComplete(false, "Backup cancelled")
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Backup", "Export failed", e)
                val friendly = VaultErrorMapper.mapBackupExportError(e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            activeErrorMessage = friendly
                        )
                    }
                    onComplete(false, friendly)
                }
            } finally {
                activeBackupJob = null
                runCatching { outputStream.close() }
            }
        }
    }

    fun inspectBackupForRestore(
        inputStream: InputStream,
        passwordChars: CharArray,
        onResult: (BackupPayload?, String?) -> Unit
    ) {
        activeRestoreJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = true,
                backupProgressRatio = null,
                backupStatusMessage = "Reading and verifying backup...",
                pendingRestoreError = null
            )
        }
        val initialSessionId = sessionManager.getCurrentSessionId()
        val stagingDir = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}").apply { mkdirs() }
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
                if (headerBytesRead < 4) {
                    throw IllegalArgumentException("Unrecognized backup file header.")
                }

                val staged = if (EncryptedBackupV2Engine.isV2Backup(headerBuf)) {
                    EncryptedBackupV2Engine.inspectAndStageBackup(bis, passwordChars, stagingDir)
                } else if (LegacyV1BackupReader.isLegacyV1Backup(headerBuf)) {
                    LegacyV1BackupReader.inspectAndStageLegacyBackup(bis, passwordChars, stagingDir)
                } else {
                    throw IllegalArgumentException("Unrecognized backup file header.")
                }
                ensureActive()
                if (hadInitializedVault && sessionManager.getCurrentSessionId() != initialSessionId) {
                    throw IllegalStateException("Session changed before backup staging completed.")
                }

                currentStagedRestore = staged
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestorePayload = staged.payload,
                            pendingRestoreError = null
                        )
                    }
                    onResult(staged.payload, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Restore inspection cancelled")
                stagingDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                    onResult(null, "Restore cancelled")
                }
            } catch (e: Exception) {
                SafeVaultLogger.w("Restore", "Failed to decrypt backup: ${e.message}")
                stagingDir.deleteRecursively()
                val friendly = VaultErrorMapper.mapRestoreError(e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestoreError = friendly
                        )
                    }
                    onResult(null, friendly.message)
                }
            } finally {
                activeRestoreJob = null
                runCatching { bis.close() }
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
        val staged = currentStagedRestore ?: run {
            val payload = _uiState.value.pendingRestorePayload ?: return
            StagedBackupRestore(payload, File(context.cacheDir, "empty_staging").apply { mkdirs() })
        }
        activeRestoreJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Restoring vault items...", pendingRestoreError = null) }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                if (sessionManager.getCurrentSessionId() != expectedSessionId) {
                    throw IllegalStateException("Session changed during restore.")
                }
                repository.commitRestore(staged, mode, isCancelled = { !isActive || sessionManager.getCurrentSessionId() != expectedSessionId })
                currentStagedRestore = null
                loadActiveVaultItems()
                withContext(Dispatchers.Main) {
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
                    onComplete(true)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Restore commit cancelled")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                    onComplete(false)
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Restore", "Restore commit failed", e)
                val friendly = VaultErrorMapper.mapRestoreError(e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestoreError = friendly
                        )
                    }
                    onComplete(false)
                }
            } finally {
                activeRestoreJob = null
            }
        }
    }

    fun createVaultAndCommitRestore(
        masterPasswordChars: CharArray,
        mode: RestoreMode = RestoreMode.REPLACE_ACTIVE_VAULT,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val staged = currentStagedRestore ?: run {
            onComplete(false, "No backup loaded for restore.")
            return
        }
        val pwdPolicy = PasswordPolicy.validateNewPassword(masterPasswordChars, "Master password")
        if (!pwdPolicy.isValid) {
            onComplete(false, pwdPolicy.errorMessage ?: "Use at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters for your new master password.")
            return
        }
        activeRestoreJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Initializing vault and restoring data...", pendingRestoreError = null) }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                val res = repository.createInitialVault(masterPasswordChars)
                if (res !is AuthResult.Success) {
                    val errMsg = (res as? AuthResult.Error)?.message ?: "Failed to initialize vault."
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isLoading = false, backupStatusMessage = null) }
                        onComplete(false, errMsg)
                    }
                    return@launch
                }
                _uiState.update { it.copy(isVaultInitialized = true, isBiometricsConfigured = false) }
                ensureActive()
                repository.commitRestore(staged, mode, isCancelled = { !isActive })
                currentStagedRestore = null
                loadActiveVaultItems()
                val isBiometricAvailable = BiometricKeystoreManager.hasKeystoreKey()
                withContext(Dispatchers.Main) {
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
                    onComplete(true, null)
                }
            } catch (e: CancellationException) {
                SafeVaultLogger.i("Restore", "Create vault and restore cancelled")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, backupProgressRatio = null, backupStatusMessage = null) }
                    onComplete(false, "Restore cancelled")
                }
            } catch (e: Exception) {
                SafeVaultLogger.e("Restore", "Create vault and restore failed", e)
                val friendly = VaultErrorMapper.mapRestoreError(e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestoreError = friendly
                        )
                    }
                    onComplete(false, friendly.message)
                }
            } finally {
                activeRestoreJob = null
            }
        }
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
                withContext(Dispatchers.Main) {
                    if (success) {
                        _uiState.update { it.copy(isBiometricsConfigured = false) }
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

    /**
     * Called on activity resume, result from Android Settings, and vault unlock.
     * Recover the current download before considering a new network request.
     */
    fun refreshOtaOnForeground() {
        if (!otaForeground || foregroundOtaJob?.isActive == true) return
        foregroundOtaJob = viewModelScope.launch {
            otaUpdateManager.recheckInstallPermissionAndVerifyCandidate()
            if (!otaForeground || !_uiState.value.isUnlocked || BuildConfig.DEBUG) return@launch
            if (!preferencesRepository.automaticOtaCheckEnabled.first()) return@launch
            val current = otaUpdateManager.otaState.value
            if (current.isPendingInstallation || current is OtaState.Downloading || current is OtaState.Verifying || current is OtaState.Checking) {
                return@launch
            }
            val now = SystemClock.elapsedRealtime()
            val last = lastAutomaticOtaAttemptElapsed
            if (last != null && now - last < 15 * 60 * 1000L) return@launch
            lastAutomaticOtaAttemptElapsed = now
            val result = otaUpdateManager.checkForUpdates(isUserInitiated = false)
            if (result is OtaState.UpdateAvailable || result is OtaState.UpToDate) {
                runCatching { preferencesRepository.setLastOtaCheckEpochMs() }
            }
        }
    }

    fun setAutomaticOtaCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutomaticOtaCheckEnabled(enabled)
            if (enabled) {
                lastAutomaticOtaAttemptElapsed = null
                refreshOtaOnForeground()
            } else {
                otaUpdateManager.dismissUpdatePrompt()
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            val recovered = otaUpdateManager.recheckInstallPermissionAndVerifyCandidate()
            if (recovered.isPendingInstallation) return@launch
            val result = otaUpdateManager.checkForUpdates(isUserInitiated = manual)
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
