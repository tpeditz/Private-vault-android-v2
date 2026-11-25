package com.tpeditz.privatevault.v2.ui

import android.content.Context
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
import com.tpeditz.privatevault.v2.security.BiometricKeystoreManager
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordGeneratorEngine
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
    private var activeRestoreJob: Job? = null

    init {
        // Collect session state
        var lastActiveSessionId: Long? = null
        viewModelScope.launch {
            sessionManager.sessionState.collect { session ->
                when (session) {
                    is VaultSessionManager.SessionState.Unlocked -> {
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
                        startTotpTicker()
                    }
                    is VaultSessionManager.SessionState.Locked -> {
                        lastActiveSessionId = null
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
                                currentSecondary = SecondaryDestination.None,
                                pendingRestorePayload = null
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
                val actualEnrolled = enabled && BiometricKeystoreManager.hasKeystoreKey()
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
                        autoCloudBackupEnabled = enabled,
                        privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = enabled)
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
                val activeAccountEmail = GoogleDriveAuthHelper.getLastSignedInAccount(context)?.email.orEmpty()
                val resolvedEmail = activeAccountEmail.ifBlank { storedEmail }
                _uiState.update {
                    it.copy(
                        connectedGoogleEmail = resolvedEmail,
                        autoCloudBackupKeyConfigured = AutoCloudBackupKeyStore.hasRecoveryPassword(context)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.lastCloudBackupEpochMs.collect { epoch ->
                _uiState.update {
                    it.copy(
                        lastCloudBackupEpochMs = epoch,
                        privacyMetrics = it.privacyMetrics.copy(lastCloudBackupEpochMs = epoch)
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
                _uiState.update { it.copy(lastCloudBackupStatusMessage = status.ifBlank { null }) }
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
        sessionManager.onAppBackgrounded()
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            val timeoutSec = preferencesRepository.autoLockTimeoutSeconds.first()
            sessionManager.onAppForegrounded(timeoutSec)
            clipboardManager.onAppForegrounded()
        }
    }

    fun grantFilePickerImmunity() {
        sessionManager.grantFilePickerImmunity()
    }

    // ============================================================================================
    // Vault Setup & Unlock
    // ============================================================================================

    fun createVault(passwordChars: CharArray, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = repository.createInitialVault(passwordChars)
            _uiState.update { it.copy(isLoading = false, isVaultInitialized = true) }
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
        viewModelScope.launch {
            val auditResult = repository.getAllItemsForActiveSlotWithAudit()
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
            val lastCloud = preferencesRepository.lastCloudBackupEpochMs.first()
            val autoBackup = preferencesRepository.autoCloudBackupEnabled.first()
            val bio = preferencesRepository.biometricUnlockEnabled.first() &&
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

            _uiState.update {
                it.copy(
                    isBiometricsConfigured = bio,
                    autoCloudBackupKeyConfigured = AutoCloudBackupKeyStore.hasRecoveryPassword(context),
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

    fun refreshSystemSecurityStatus() {
        viewModelScope.launch {
            val liveAutofill = AutofillHelper.isAutofillServiceEnabled(context)
            runCatching { preferencesRepository.setAutofillEnabled(liveAutofill) }
            val bioPref = preferencesRepository.biometricUnlockEnabled.first()
            val actualBio = bioPref && BiometricKeystoreManager.hasKeystoreKey()
            val storedEmail = preferencesRepository.connectedGoogleAccount.first()
            val signedInEmail = GoogleDriveAuthHelper.getLastSignedInAccount(context)?.email.orEmpty()
            val resolvedEmail = signedInEmail.ifBlank { storedEmail }
            val hasRecoveryPwd = AutoCloudBackupKeyStore.hasRecoveryPassword(context)
            val currentOta = otaUpdateManager.otaState.value
            val updateAvailable = currentOta is OtaState.UpdateAvailable ||
                currentOta is OtaState.ReadyToInstall ||
                currentOta is OtaState.NeedsUnknownSourcesPermission
            _uiState.update {
                it.copy(
                    isBiometricsConfigured = actualBio,
                    connectedGoogleEmail = resolvedEmail,
                    autoCloudBackupKeyConfigured = hasRecoveryPwd,
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
                withContext(Dispatchers.Main) { onComplete?.invoke(ok, if (ok) null else "Decoy creation failed") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete?.invoke(false, e.message) }
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

    fun onGoogleAccountConnected(email: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                preferencesRepository.setConnectedGoogleAccount(email)
                preferencesRepository.setAutoCloudBackupStatus("Connected to $email")
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
                            connectedGoogleEmail = email,
                            lastCloudBackupStatusMessage = "Connected to $email"
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
                CloudBackupScheduler.cancelAllCloudWork(context)
                CloudBackupScheduler.clearPendingSnapshot(context)
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
                            lastCloudBackupStatusMessage = "Not connected",
                            privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = false)
                        )
                    }
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Failed to disconnect") }
            }
        }
    }

    fun configureAutomaticCloudBackup(
        enabled: Boolean,
        recoveryPasswordChars: CharArray?,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (enabled) {
                    val account = GoogleDriveAuthHelper.getLastSignedInAccount(context)
                        ?: return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Connect a Google account first.")
                        }
                    if (!GoogleDriveAuthHelper.hasPermissions(context)) {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Google Drive permission is missing. Reconnect your account.")
                        }
                    }
                    if (recoveryPasswordChars != null) {
                        if (recoveryPasswordChars.size < 8) {
                            return@launch withContext(Dispatchers.Main) {
                                onComplete(false, "Recovery password must be at least 8 characters.")
                            }
                        }
                        val stored = AutoCloudBackupKeyStore.storeRecoveryPassword(context, recoveryPasswordChars)
                        if (!stored) {
                            return@launch withContext(Dispatchers.Main) {
                                onComplete(false, "Could not protect recovery password in Android Keystore.")
                            }
                        }
                    } else if (!AutoCloudBackupKeyStore.hasRecoveryPassword(context)) {
                        return@launch withContext(Dispatchers.Main) {
                            onComplete(false, "Please enter a backup recovery password first.")
                        }
                    }
                    val freq = preferencesRepository.cloudBackupFrequency.first()
                    val wifiOnly = preferencesRepository.cloudBackupWifiOnly.first()
                    preferencesRepository.setConnectedGoogleAccount(account.email.orEmpty())
                    preferencesRepository.setAutoCloudBackupEnabled(true)
                    preferencesRepository.setAutoCloudBackupStatus("Automatic backup scheduled")
                    CloudBackupScheduler.schedulePeriodicBackup(context, freq, wifiOnly)
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                autoCloudBackupEnabled = true,
                                autoCloudBackupKeyConfigured = true,
                                lastCloudBackupStatusMessage = "Automatic backup scheduled",
                                privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = true)
                            )
                        }
                        onComplete(true, null)
                    }
                    preparePendingAutoCloudBackupIfNeeded(forceImmediateTrigger = true)
                } else {
                    CloudBackupScheduler.cancelAllCloudWork(context)
                    CloudBackupScheduler.clearPendingSnapshot(context)
                    AutoCloudBackupKeyStore.clearRecoveryPassword(context)
                    preferencesRepository.setAutoCloudBackupEnabled(false)
                    preferencesRepository.setAutoCloudBackupStatus("Automatic backup turned off")
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                autoCloudBackupEnabled = false,
                                autoCloudBackupKeyConfigured = false,
                                lastCloudBackupStatusMessage = "Automatic backup turned off",
                                privacyMetrics = it.privacyMetrics.copy(isAutoBackupEnabled = false)
                            )
                        }
                        onComplete(true, null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Failed to update automatic backup") }
            } finally {
                if (recoveryPasswordChars != null) {
                    Arrays.fill(recoveryPasswordChars, '\u0000')
                }
            }
        }
    }

    fun setCloudBackupFrequency(frequency: CloudBackupFrequency) {
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
            val account = GoogleDriveAuthHelper.getLastSignedInAccount(context) ?: return@launch
            val email = account.email.orEmpty()
            if (email.isBlank()) return@launch
            val recoveryPwd = AutoCloudBackupKeyStore.readRecoveryPasswordOrNull(context) ?: return@launch
            try {
                val items = repository.getAllItemsForActiveSlot()
                val pendingFile = CloudBackupScheduler.pendingSnapshotFile(context)
                val tmpFile = File(pendingFile.parentFile, "auto_snapshot.tmp")
                FileOutputStream(tmpFile).use { fos ->
                    repository.createAuthoritativeStreamingBackup(
                        items = items,
                        backupPasswordChars = recoveryPwd,
                        outputStream = fos
                    )
                    fos.flush()
                    fos.fd.sync()
                }
                if (pendingFile.exists()) pendingFile.delete()
                tmpFile.renameTo(pendingFile)
                CloudBackupScheduler.writePendingSnapshotMeta(
                    context,
                    CloudBackupScheduler.PendingSnapshotMeta(
                        accountEmail = email,
                        preparedAtEpochMs = System.currentTimeMillis(),
                        sizeBytes = pendingFile.length()
                    )
                )
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

    fun uploadCloudBackup(backupPasswordChars: CharArray?, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempBackupFile = File(context.cacheDir, "cloud_snapshot_${System.currentTimeMillis()}.pvault")
            var resolvedChars: CharArray? = null
            var ownsResolvedChars = false
            try {
                val activePwd = if (backupPasswordChars != null && backupPasswordChars.isNotEmpty()) {
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

                val account = GoogleDriveAuthHelper.getLastSignedInAccount(context)
                    ?: throw IllegalStateException("Google Drive account is not connected.")
                if (!GoogleDriveAuthHelper.hasPermissions(context)) {
                    throw IllegalStateException("Google Drive permission was revoked. Please reconnect your account.")
                }
                var token = GoogleDriveAuthHelper.getAccessToken(context, account).getOrThrow()

                val allItems = repository.getAllItemsForActiveSlot()
                FileOutputStream(tempBackupFile).use { fos ->
                    repository.createAuthoritativeStreamingBackup(
                        items = allItems,
                        backupPasswordChars = activePwd,
                        outputStream = fos
                    )
                    fos.flush()
                    fos.fd.sync()
                }
                var res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.refreshAccessToken(context, account, token).getOrNull()
                    if (refreshed != null) {
                        token = refreshed
                        res = googleDriveSyncManager.uploadBackupSnapshotFile(token, tempBackupFile)
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
                        preferencesRepository.setAutoCloudBackupStatus("Last upload failed: ${res.message}")
                        withContext(Dispatchers.Main) { onComplete(false, res.message) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Cloud upload failed") }
            } finally {
                tempBackupFile.delete()
                if (ownsResolvedChars && resolvedChars != null) {
                    Arrays.fill(resolvedChars, '\u0000')
                }
            }
        }
    }

    fun downloadCloudSnapshot(fileId: String, onDownloaded: (File?, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = File(context.cacheDir, "cloud_download_${fileId.take(8)}_${System.currentTimeMillis()}.pvault")
            try {
                val account = GoogleDriveAuthHelper.getLastSignedInAccount(context)
                    ?: throw IllegalStateException("Google Drive account is not connected.")
                var token = GoogleDriveAuthHelper.getAccessToken(context, account).getOrThrow()

                var res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.refreshAccessToken(context, account, token).getOrNull()
                    if (refreshed != null) {
                        token = refreshed
                        res = googleDriveSyncManager.downloadBackupSnapshotToFile(token, fileId, targetFile)
                    }
                }
                when (res) {
                    is GoogleDriveSyncManager.SyncResult.Success -> {
                        withContext(Dispatchers.Main) { onDownloaded(res.data, null) }
                    }
                    is GoogleDriveSyncManager.SyncResult.Error -> {
                        withContext(Dispatchers.Main) { onDownloaded(null, res.message) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDownloaded(null, e.message ?: "Failed to download snapshot") }
            }
        }
    }

    fun queryCloudSnapshots(onResult: (List<GoogleDriveSyncManager.CloudBackupMetadata>, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleDriveAuthHelper.getLastSignedInAccount(context)
                if (account == null) {
                    withContext(Dispatchers.Main) { onResult(emptyList(), "Google account is not connected.") }
                    return@launch
                }
                if (!GoogleDriveAuthHelper.hasPermissions(context)) {
                    withContext(Dispatchers.Main) { onResult(emptyList(), "Google Drive access was revoked. Reconnect your account.") }
                    return@launch
                }
                var token = GoogleDriveAuthHelper.getAccessToken(context, account).getOrElse { err ->
                    withContext(Dispatchers.Main) { onResult(emptyList(), err.message ?: "Could not access Google Drive.") }
                    return@launch
                }
                var res = googleDriveSyncManager.queryCloudBackups(token)
                if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError) {
                    val refreshed = GoogleDriveAuthHelper.refreshAccessToken(context, account, token).getOrNull()
                    if (refreshed != null) {
                        token = refreshed
                        res = googleDriveSyncManager.queryCloudBackups(token)
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
                withContext(Dispatchers.Main) { onResult(emptyList(), e.message ?: "Could not load backups") }
            }
        }
    }

    fun deleteCloudSnapshot(fileId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleDriveAuthHelper.getLastSignedInAccount(context)
                var token = account?.let { GoogleDriveAuthHelper.getAccessToken(context, it).getOrNull() }
                if (token != null) {
                    var res = googleDriveSyncManager.deleteCloudBackup(token, fileId)
                    if (res is GoogleDriveSyncManager.SyncResult.Error && res.isAuthError && account != null) {
                        val refreshed = GoogleDriveAuthHelper.refreshAccessToken(context, account, token).getOrNull()
                        if (refreshed != null) {
                            token = refreshed
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

    fun exportStreamingBackup(
        scope: BackupExportScope,
        passwordChars: CharArray,
        outputStream: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onComplete: (Boolean, String?) -> Unit
    ) {
        activeBackupJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = 0f, backupStatusMessage = "Preparing backup...") }
        activeBackupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val allItems = repository.getAllItemsForActiveSlot()
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
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            activeErrorMessage = e.message ?: "Backup failed"
                        )
                    }
                    onComplete(false, e.message)
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
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Reading and verifying backup...") }
        val stagingDir = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        val bis = BufferedInputStream(inputStream)
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                val headerBuf = ByteArray(8)
                bis.mark(16)
                bis.read(headerBuf)
                bis.reset()

                val staged = if (EncryptedBackupV2Engine.isV2Backup(headerBuf)) {
                    EncryptedBackupV2Engine.inspectAndStageBackup(bis, passwordChars, stagingDir)
                } else if (LegacyV1BackupReader.isLegacyV1Backup(headerBuf)) {
                    LegacyV1BackupReader.inspectAndStageLegacyBackup(bis, passwordChars, stagingDir)
                } else {
                    throw IllegalArgumentException("Unrecognized backup file header.")
                }
                ensureActive()

                currentStagedRestore = staged
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestorePayload = staged.payload
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
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            activeErrorMessage = e.message ?: "Failed to inspect backup"
                        )
                    }
                    onResult(null, e.message)
                }
            } finally {
                activeRestoreJob = null
                runCatching { bis.close() }
            }
        }
    }

    fun confirmRestore(mode: RestoreMode, onComplete: (Boolean) -> Unit) {
        val staged = currentStagedRestore ?: run {
            val payload = _uiState.value.pendingRestorePayload ?: return
            StagedBackupRestore(payload, File(context.cacheDir, "empty_staging").apply { mkdirs() })
        }
        activeRestoreJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Restoring vault items...") }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                repository.commitRestore(staged, mode, isCancelled = { !isActive })
                currentStagedRestore = null
                loadActiveVaultItems()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            pendingRestorePayload = null,
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
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            activeErrorMessage = e.message ?: "Restore commit failed"
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
        activeRestoreJob?.cancel()
        _uiState.update { it.copy(isLoading = true, backupProgressRatio = null, backupStatusMessage = "Initializing vault and restoring data...") }
        activeRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive()
                val res = repository.createInitialVault(masterPasswordChars)
                if (res !is AuthResult.Success) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isLoading = false, backupStatusMessage = null) }
                        onComplete(false, "Failed to initialize vault envelope.")
                    }
                    return@launch
                }
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
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            backupProgressRatio = null,
                            backupStatusMessage = null,
                            activeErrorMessage = e.message ?: "Restore failed"
                        )
                    }
                    onComplete(false, e.message ?: "Restore failed")
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = repository.changeMasterPassword(currentPasswordChars, newPasswordChars)
                withContext(Dispatchers.Main) {
                    if (success) {
                        _uiState.update { it.copy(isBiometricsConfigured = false) }
                        onComplete(true, null)
                    } else {
                        onComplete(false, "Current password incorrect.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, e.message ?: "Failed to change password") }
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        viewModelScope.launch {
            otaUpdateManager.checkForUpdates(isUserInitiated = manual)
            runCatching { preferencesRepository.setLastOtaCheckEpochMs() }
        }
    }

    fun startUpdateDownload(manifest: com.tpeditz.privatevault.v2.ota.UpdateManifest) {
        viewModelScope.launch {
            otaUpdateManager.startDownload(manifest)
        }
    }

    fun cancelUpdateDownload() {
        otaUpdateManager.cancelActiveDownload()
    }

    fun launchPackageInstaller(apkFile: File) {
        otaUpdateManager.launchPackageInstaller(apkFile)
    }

    fun openUnknownSourcesSettings() {
        otaUpdateManager.openUnknownSourcesSettings()
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
