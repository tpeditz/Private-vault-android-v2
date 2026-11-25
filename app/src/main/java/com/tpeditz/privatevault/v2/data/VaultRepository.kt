package com.tpeditz.privatevault.v2.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.tpeditz.privatevault.v2.security.BiometricKeystoreManager
import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.CancellationException
import org.bouncycastle.util.encoders.Base64
import java.util.UUID
import javax.crypto.Cipher

sealed interface AuthResult {
    data class Success(val slotId: Int, val isDecoyPolicyActive: Boolean) : AuthResult
    data object IncorrectPassword : AuthResult
    data object InvalidPassword : AuthResult
    data class LockedOut(val remainingSeconds: Int) : AuthResult
    data class Error(val message: String) : AuthResult
}

private fun safeGetDatabase(context: Context): PrivateVaultDatabase? = try {
    PrivateVaultDatabase.getInstance(context)
} catch (_: Throwable) {
    null
}

class VaultRepository(
    private val context: Context?,
    private val database: PrivateVaultDatabase?,
    private val dao: VaultDao,
    private val sessionManager: VaultSessionManager,
    private val preferencesRepository: AppPreferencesRepository? = null,
    private val baseFilesDir: File? = null,
    private val baseCacheDir: File? = null
) {
    val filesDir: File get() = baseFilesDir ?: context?.filesDir ?: throw IllegalStateException("filesDir not configured")
    val cacheDir: File get() = baseCacheDir ?: context?.cacheDir ?: throw IllegalStateException("cacheDir not configured")

    constructor(
        context: Context,
        database: PrivateVaultDatabase,
        sessionManager: VaultSessionManager,
        preferencesRepository: AppPreferencesRepository? = null
    ) : this(
        context = context,
        database = database,
        dao = database.vaultDao(),
        sessionManager = sessionManager,
        preferencesRepository = preferencesRepository,
        baseFilesDir = null,
        baseCacheDir = null
    )

    constructor(
        context: Context,
        dao: VaultDao,
        sessionManager: VaultSessionManager,
        preferencesRepository: AppPreferencesRepository? = null
    ) : this(
        context = context,
        database = safeGetDatabase(context),
        dao = dao,
        sessionManager = sessionManager,
        preferencesRepository = preferencesRepository,
        baseFilesDir = null,
        baseCacheDir = null
    )

    constructor(
        dao: VaultDao,
        sessionManager: VaultSessionManager,
        filesDir: File,
        cacheDir: File
    ) : this(
        context = null,
        database = null,
        dao = dao,
        sessionManager = sessionManager,
        preferencesRepository = null,
        baseFilesDir = filesDir,
        baseCacheDir = cacheDir
    )

    companion object {
        const val RECOVERY_JOURNAL_DIR_NAME = "restore_recovery_journal"
        const val RECOVERY_MANIFEST_FILE_NAME = "restore_manifest.json"
        const val STATE_PREPARING = "PREPARING"
        const val STATE_STAGED = "STAGED"
        const val STATE_COPIED = "COPIED"
        const val STATE_COMMITTED = "COMMITTED"
    }

    interface TransactionalVaultDao {
        suspend fun <T> withTransaction(block: suspend () -> T): T
    }

    private val kdfMutex = Mutex()
    private val vaultMutationMutex = Mutex()

    init {
        runCatching { recoverIncompleteRestoreIfPresent() }
    }

    @Volatile
    private var failedAttemptCount = 0

    @Volatile
    private var lockoutUntilMonotonicMs = 0L

    fun getLockoutRemainingSeconds(): Int {
        val now = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { System.currentTimeMillis() }
        val remainingMs = lockoutUntilMonotonicMs - now
        return if (remainingMs > 0) ((remainingMs + 999) / 1000).toInt() else 0
    }

    suspend fun isVaultInitialized(): Boolean = withContext(Dispatchers.IO) {
        dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) != null
    }

    suspend fun isDecoyConfigured(): Boolean = withContext(Dispatchers.IO) {
        val decoyEnv = dao.getEnvelopeForSlot(VaultSessionManager.DECOY_SLOT) ?: return@withContext false
        val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withContext false
        // Slot 0 holds the authoritative decoyPolicyFlag inside its authenticated envelope,
        // but if unlocked we check session. If locked we check the DB record.
        true
    }

    /**
     * Initializes a brand-new vault with Slot 0 (Real) and Slot 1 (dummy sealed Decoy).
     */
    suspend fun createInitialVault(
        masterPasswordChars: CharArray,
        kdfParams: CryptoEngine.KdfParams = CryptoEngine.KdfParams()
    ): AuthResult = withContext(Dispatchers.IO) {
        kdfMutex.withLock {
            require(masterPasswordChars.isNotEmpty()) { "Master password cannot be empty." }
            kdfParams.validateBounds()

            val now = System.currentTimeMillis()
            val realSalt = CryptoEngine.generateSalt()
            val realNonce = CryptoEngine.generateNonce()
            val realDek = CryptoEngine.generateDek()

            var realKek: ByteArray? = null
            try {
                realKek = CryptoEngine.deriveKeyArgon2id(
                    passwordChars = masterPasswordChars,
                    salt = realSalt,
                    params = kdfParams,
                    normalizeUnicodeNfc = true
                )

                val realPayload = CryptoEngine.UnwrappedEnvelopePayload(
                    slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
                    decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
                    createdAtEpochMs = now,
                    rotatedAtEpochMs = now,
                    dekBytes = realDek
                )

                val wrappedReal = CryptoEngine.wrapEnvelopePayload(
                    kekBytes = realKek,
                    nonce = realNonce,
                    slotId = VaultSessionManager.REAL_SLOT,
                    envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                    params = kdfParams,
                    payload = realPayload
                )

                val realEnvelope = VaultEnvelopeEntity(
                    slotId = VaultSessionManager.REAL_SLOT,
                    envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                    kdfAlgorithm = kdfParams.algorithm,
                    kdfSalt = realSalt,
                    kdfMemoryKb = kdfParams.memoryKb,
                    kdfIterations = kdfParams.iterations,
                    kdfParallelism = kdfParams.parallelism,
                    envelopeNonce = realNonce,
                    wrappedPayloadCiphertext = wrappedReal
                )

                // Slot 1 (Decoy) dummy envelope: random unrecoverable KEK
                val decoySalt = CryptoEngine.generateSalt()
                val decoyNonce = CryptoEngine.generateNonce()
                val dummyDek = CryptoEngine.generateDek()
                val dummyKek = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)

                val dummyPayload = CryptoEngine.UnwrappedEnvelopePayload(
                    slotRoleFlag = CryptoEngine.SLOT_ROLE_DUMMY,
                    decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
                    createdAtEpochMs = now,
                    rotatedAtEpochMs = now,
                    dekBytes = dummyDek
                )

                val wrappedDecoy = CryptoEngine.wrapEnvelopePayload(
                    kekBytes = dummyKek,
                    nonce = decoyNonce,
                    slotId = VaultSessionManager.DECOY_SLOT,
                    envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                    params = kdfParams,
                    payload = dummyPayload
                )

                val decoyEnvelope = VaultEnvelopeEntity(
                    slotId = VaultSessionManager.DECOY_SLOT,
                    envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                    kdfAlgorithm = kdfParams.algorithm,
                    kdfSalt = decoySalt,
                    kdfMemoryKb = kdfParams.memoryKb,
                    kdfIterations = kdfParams.iterations,
                    kdfParallelism = kdfParams.parallelism,
                    envelopeNonce = decoyNonce,
                    wrappedPayloadCiphertext = wrappedDecoy
                )

                // Provision default folders for Slot 0
                val defaultFolders = listOf(
                    VaultFolder(
                        id = VaultFolder.DEFAULT_NOTE_FOLDER_ID,
                        name = VaultFolder.DEFAULT_FOLDER_NAME,
                        scope = FolderScope.NOTES,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    ),
                    VaultFolder(
                        id = VaultFolder.DEFAULT_CREDENTIAL_FOLDER_ID,
                        name = VaultFolder.DEFAULT_FOLDER_NAME,
                        scope = FolderScope.CREDENTIALS,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    ),
                    VaultFolder(
                        id = VaultFolder.DEFAULT_DOCUMENT_FOLDER_ID,
                        name = VaultFolder.DEFAULT_FOLDER_NAME,
                        scope = FolderScope.DOCUMENTS,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )

                val initialEncryptedRecords = defaultFolders.map { folder ->
                    val encoded = VaultRecordCodec.encode(folder)
                    val nonce = CryptoEngine.generateNonce()
                    val aad = CryptoEngine.buildRecordAad(VaultSessionManager.REAL_SLOT, folder.id)
                    val cipher = CryptoEngine.encryptAesGcm(realDek, nonce, encoded, aad)
                    EncryptedRecordEntity(
                        recordId = folder.id,
                        vaultSlot = VaultSessionManager.REAL_SLOT,
                        schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                        nonce = nonce,
                        ciphertext = cipher
                    )
                }

                dao.initializeVaultAtomically(
                    envelopes = listOf(realEnvelope, decoyEnvelope),
                    initialRecords = initialEncryptedRecords
                )

                sessionManager.openSession(
                    slotId = VaultSessionManager.REAL_SLOT,
                    dekBytes = realDek,
                    isDecoyConfiguredInRealVault = false
                )

                AuthResult.Success(VaultSessionManager.REAL_SLOT, false)
            } finally {
                realKek?.let { Arrays.fill(it, 0.toByte()) }
                Arrays.fill(realDek, 0.toByte())
            }
        }
    }

    /**
     * Unlocks the vault using the supplied password. Checks Slot 0 first, then Slot 1.
     */
    suspend fun unlockVault(passwordChars: CharArray): AuthResult = withContext(Dispatchers.IO) {
        kdfMutex.withLock {
            val remainingLockout = getLockoutRemainingSeconds()
            if (remainingLockout > 0) {
                return@withLock AuthResult.LockedOut(remainingLockout)
            }

            val authGen = sessionManager.currentAuthRequestGeneration()
            val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)
                ?: return@withLock AuthResult.Error("Vault is not initialized.")
            val decoyEnv = dao.getEnvelopeForSlot(VaultSessionManager.DECOY_SLOT)

            val realParams = CryptoEngine.KdfParams(
                algorithm = realEnv.kdfAlgorithm,
                memoryKb = realEnv.kdfMemoryKb,
                iterations = realEnv.kdfIterations,
                parallelism = realEnv.kdfParallelism
            )

            // 1. Try Slot 0 (Real Vault)
            var realKek: ByteArray? = null
            var realPayload: CryptoEngine.UnwrappedEnvelopePayload? = null
            try {
                realKek = CryptoEngine.deriveKeyArgon2id(
                    passwordChars = passwordChars,
                    salt = realEnv.kdfSalt,
                    params = realParams,
                    normalizeUnicodeNfc = true
                )
                realPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                    kekBytes = realKek,
                    nonce = realEnv.envelopeNonce,
                    slotId = VaultSessionManager.REAL_SLOT,
                    envelopeVersion = realEnv.envelopeVersion,
                    params = realParams,
                    wrappedCiphertext = realEnv.wrappedPayloadCiphertext
                )

                if (realPayload == null && CryptoEngine.hasDistinctLegacyNonNfcForm(passwordChars)) {
                    Arrays.fill(realKek, 0.toByte())
                    realKek = CryptoEngine.deriveKeyArgon2id(
                        passwordChars = passwordChars,
                        salt = realEnv.kdfSalt,
                        params = realParams,
                        normalizeUnicodeNfc = false
                    )
                    realPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                        kekBytes = realKek,
                        nonce = realEnv.envelopeNonce,
                        slotId = VaultSessionManager.REAL_SLOT,
                        envelopeVersion = realEnv.envelopeVersion,
                        params = realParams,
                        wrappedCiphertext = realEnv.wrappedPayloadCiphertext
                    )
                }

                if (realPayload != null && realPayload.isActiveVault) {
                    val session = sessionManager.openSessionIfGenerationMatches(
                        expectedAuthGeneration = authGen,
                        slotId = VaultSessionManager.REAL_SLOT,
                        dekBytes = realPayload.dekBytes,
                        isDecoyConfiguredInRealVault = realPayload.isDecoyPolicyEnabled
                    )
                    return@withLock if (session != null) {
                        failedAttemptCount = 0
                        lockoutUntilMonotonicMs = 0L
                        AuthResult.Success(VaultSessionManager.REAL_SLOT, realPayload.isDecoyPolicyEnabled)
                    } else {
                        AuthResult.Error("Unlock interrupted by screen-off or lock event.")
                    }
                }
            } finally {
                realKek?.let { Arrays.fill(it, 0.toByte()) }
                realPayload?.wipe()
            }

            // 2. Try Slot 1 (Decoy Vault)
            if (decoyEnv != null) {
                val decoyParams = CryptoEngine.KdfParams(
                    algorithm = decoyEnv.kdfAlgorithm,
                    memoryKb = decoyEnv.kdfMemoryKb,
                    iterations = decoyEnv.kdfIterations,
                    parallelism = decoyEnv.kdfParallelism
                )
                var decoyKek: ByteArray? = null
                var decoyPayload: CryptoEngine.UnwrappedEnvelopePayload? = null
                try {
                    decoyKek = CryptoEngine.deriveKeyArgon2id(
                        passwordChars = passwordChars,
                        salt = decoyEnv.kdfSalt,
                        params = decoyParams,
                        normalizeUnicodeNfc = true
                    )
                    decoyPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                        kekBytes = decoyKek,
                        nonce = decoyEnv.envelopeNonce,
                        slotId = VaultSessionManager.DECOY_SLOT,
                        envelopeVersion = decoyEnv.envelopeVersion,
                        params = decoyParams,
                        wrappedCiphertext = decoyEnv.wrappedPayloadCiphertext
                    )

                    if (decoyPayload == null && CryptoEngine.hasDistinctLegacyNonNfcForm(passwordChars)) {
                        Arrays.fill(decoyKek, 0.toByte())
                        decoyKek = CryptoEngine.deriveKeyArgon2id(
                            passwordChars = passwordChars,
                            salt = decoyEnv.kdfSalt,
                            params = decoyParams,
                            normalizeUnicodeNfc = false
                        )
                        decoyPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                            kekBytes = decoyKek,
                            nonce = decoyEnv.envelopeNonce,
                            slotId = VaultSessionManager.DECOY_SLOT,
                            envelopeVersion = decoyEnv.envelopeVersion,
                            params = decoyParams,
                            wrappedCiphertext = decoyEnv.wrappedPayloadCiphertext
                        )
                    }

                    if (decoyPayload != null && decoyPayload.isActiveVault) {
                        val session = sessionManager.openSessionIfGenerationMatches(
                            expectedAuthGeneration = authGen,
                            slotId = VaultSessionManager.DECOY_SLOT,
                            dekBytes = decoyPayload.dekBytes,
                            isDecoyConfiguredInRealVault = false
                        )
                        return@withLock if (session != null) {
                            failedAttemptCount = 0
                            lockoutUntilMonotonicMs = 0L
                            AuthResult.Success(VaultSessionManager.DECOY_SLOT, false)
                        } else {
                            AuthResult.Error("Unlock interrupted by lock event.")
                        }
                    }
                } finally {
                    decoyKek?.let { Arrays.fill(it, 0.toByte()) }
                    decoyPayload?.wipe()
                }
            }

            failedAttemptCount++
            val nowMs = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { System.currentTimeMillis() }
            if (failedAttemptCount >= 10) {
                lockoutUntilMonotonicMs = nowMs + 60_000L
            } else if (failedAttemptCount >= 5) {
                lockoutUntilMonotonicMs = nowMs + 30_000L
            }
            val lockoutRem = getLockoutRemainingSeconds()
            if (lockoutRem > 0) {
                AuthResult.LockedOut(lockoutRem)
            } else {
                AuthResult.IncorrectPassword
            }
        }
    }

    suspend fun getBiometricIvForUnlock(): ByteArray? = withContext(Dispatchers.IO) {
        val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)
        realEnv?.biometricNonce
    }

    /**
     * Unlocks Slot 0 using Android Keystore wrapped biometric key.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): AuthResult = withContext(Dispatchers.IO) {
        val authGen = sessionManager.currentAuthRequestGeneration()
        val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)
            ?: return@withContext AuthResult.Error("Vault not initialized.")
        val bioWrapped = realEnv.biometricWrappedDek
            ?: return@withContext AuthResult.Error("Biometric unlock not enrolled.")

        var dekBytes: ByteArray? = null
        try {
            dekBytes = BiometricKeystoreManager.unwrapDekWithCipher(cipher, bioWrapped)
            val session = sessionManager.openSessionIfGenerationMatches(
                expectedAuthGeneration = authGen,
                slotId = VaultSessionManager.REAL_SLOT,
                dekBytes = dekBytes,
                isDecoyConfiguredInRealVault = false
            )
            if (session != null) {
                failedAttemptCount = 0
                lockoutUntilMonotonicMs = 0L
                AuthResult.Success(VaultSessionManager.REAL_SLOT, false)
            } else {
                AuthResult.Error("Biometric unlock interrupted by lock event.")
            }
        } catch (e: Exception) {
            AuthResult.Error("Biometric decryption failed: ${e.message}")
        } finally {
            dekBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Enrolls biometrics for Slot 0.
     */
    suspend fun enrollBiometricKey(cipher: Cipher): Boolean = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        require(session.slotId == VaultSessionManager.REAL_SLOT) { "Biometrics allowed for Real Vault only." }

        val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withContext false
        val dek = session.borrowDekCopy()
        try {
            val wrapped = BiometricKeystoreManager.wrapDekWithCipher(cipher, dek)
            val updated = realEnv.copy(
                biometricNonce = wrapped.iv,
                biometricWrappedDek = wrapped.ciphertext
            )
            dao.upsertEnvelope(updated)
            true
        } finally {
            Arrays.fill(dek, 0.toByte())
        }
    }

    suspend fun disableBiometricKey(): Unit = withContext(Dispatchers.IO) {
        BiometricKeystoreManager.deleteBiometricKey()
        val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withContext
        val updated = realEnv.copy(
            biometricNonce = null,
            biometricWrappedDek = null
        )
        dao.upsertEnvelope(updated)
    }

    /**
     * Provisions Decoy Mode (Slot 1) after verifying Slot 0 password.
     */
    suspend fun provisionDecoyVault(
        primaryPasswordChars: CharArray,
        decoyPasswordChars: CharArray
    ): Boolean = withContext(Dispatchers.IO) {
        kdfMutex.withLock {
            val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withLock false
            val decoyEnv = dao.getEnvelopeForSlot(VaultSessionManager.DECOY_SLOT) ?: return@withLock false

            // Verify primary password first
            val realParams = CryptoEngine.KdfParams(
                algorithm = realEnv.kdfAlgorithm,
                memoryKb = realEnv.kdfMemoryKb,
                iterations = realEnv.kdfIterations,
                parallelism = realEnv.kdfParallelism
            )
            val realKek = CryptoEngine.deriveKeyArgon2id(primaryPasswordChars, realEnv.kdfSalt, realParams)
            val realPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                kekBytes = realKek,
                nonce = realEnv.envelopeNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = realEnv.envelopeVersion,
                params = realParams,
                wrappedCiphertext = realEnv.wrappedPayloadCiphertext
            )
            if (realPayload == null) {
                Arrays.fill(realKek, 0.toByte())
                return@withLock false
            }

            // Update Slot 0 envelope to set decoyPolicyFlag = 1 and clear biometric
            val updatedRealPayload = realPayload.copy(decoyPolicyFlag = CryptoEngine.DECOY_POLICY_ENABLED)
            val newRealNonce = CryptoEngine.generateNonce()
            val newWrappedReal = CryptoEngine.wrapEnvelopePayload(
                kekBytes = realKek,
                nonce = newRealNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = realEnv.envelopeVersion,
                params = realParams,
                payload = updatedRealPayload
            )
            Arrays.fill(realKek, 0.toByte())
            realPayload.wipe()

            val updatedRealEnv = realEnv.copy(
                envelopeNonce = newRealNonce,
                wrappedPayloadCiphertext = newWrappedReal,
                biometricNonce = null,
                biometricWrappedDek = null
            )
            BiometricKeystoreManager.deleteBiometricKey()

            // Provision Slot 1 (Decoy)
            val decoyParams = CryptoEngine.KdfParams()
            val decoySalt = CryptoEngine.generateSalt()
            val decoyNonce = CryptoEngine.generateNonce()
            val decoyDek = CryptoEngine.generateDek()
            val decoyKek = CryptoEngine.deriveKeyArgon2id(decoyPasswordChars, decoySalt, decoyParams)

            val now = System.currentTimeMillis()
            val decoyPayload = CryptoEngine.UnwrappedEnvelopePayload(
                slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
                decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
                createdAtEpochMs = now,
                rotatedAtEpochMs = now,
                dekBytes = decoyDek
            )
            val wrappedDecoy = CryptoEngine.wrapEnvelopePayload(
                kekBytes = decoyKek,
                nonce = decoyNonce,
                slotId = VaultSessionManager.DECOY_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                params = decoyParams,
                payload = decoyPayload
            )
            Arrays.fill(decoyKek, 0.toByte())

            val updatedDecoyEnv = VaultEnvelopeEntity(
                slotId = VaultSessionManager.DECOY_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                kdfAlgorithm = decoyParams.algorithm,
                kdfSalt = decoySalt,
                kdfMemoryKb = decoyParams.memoryKb,
                kdfIterations = decoyParams.iterations,
                kdfParallelism = decoyParams.parallelism,
                envelopeNonce = decoyNonce,
                wrappedPayloadCiphertext = wrappedDecoy
            )

            // Default folders for Decoy slot
            val decoyFolders = listOf(
                VaultFolder(
                    id = UUID.randomUUID().toString(),
                    name = VaultFolder.DEFAULT_FOLDER_NAME,
                    scope = FolderScope.NOTES,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                ),
                VaultFolder(
                    id = UUID.randomUUID().toString(),
                    name = VaultFolder.DEFAULT_FOLDER_NAME,
                    scope = FolderScope.CREDENTIALS,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            val seedRecords = decoyFolders.map { folder ->
                val encoded = VaultRecordCodec.encode(folder)
                val nonce = CryptoEngine.generateNonce()
                val aad = CryptoEngine.buildRecordAad(VaultSessionManager.DECOY_SLOT, folder.id)
                val cipher = CryptoEngine.encryptAesGcm(decoyDek, nonce, encoded, aad)
                EncryptedRecordEntity(
                    recordId = folder.id,
                    vaultSlot = VaultSessionManager.DECOY_SLOT,
                    schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                    nonce = nonce,
                    ciphertext = cipher
                )
            }
            Arrays.fill(decoyDek, 0.toByte())

            dao.provisionOrResetDecoyAtomically(
                updatedRealEnvelope = updatedRealEnv,
                updatedDecoyEnvelope = updatedDecoyEnv,
                decoySlotId = VaultSessionManager.DECOY_SLOT,
                seedRecords = seedRecords
            )
            true
        }
    }

    /**
     * Disables Decoy Mode and wipes Slot 1 records atomically.
     */
    suspend fun disableDecoyVault(primaryPasswordChars: CharArray): Boolean = withContext(Dispatchers.IO) {
        kdfMutex.withLock {
            val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withLock false
            val decoyEnv = dao.getEnvelopeForSlot(VaultSessionManager.DECOY_SLOT) ?: return@withLock false

            val realParams = CryptoEngine.KdfParams(
                algorithm = realEnv.kdfAlgorithm,
                memoryKb = realEnv.kdfMemoryKb,
                iterations = realEnv.kdfIterations,
                parallelism = realEnv.kdfParallelism
            )
            val realKek = CryptoEngine.deriveKeyArgon2id(primaryPasswordChars, realEnv.kdfSalt, realParams)
            val realPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                kekBytes = realKek,
                nonce = realEnv.envelopeNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = realEnv.envelopeVersion,
                params = realParams,
                wrappedCiphertext = realEnv.wrappedPayloadCiphertext
            )
            if (realPayload == null) {
                Arrays.fill(realKek, 0.toByte())
                return@withLock false
            }

            val updatedRealPayload = realPayload.copy(decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED)
            val newRealNonce = CryptoEngine.generateNonce()
            val newWrappedReal = CryptoEngine.wrapEnvelopePayload(
                kekBytes = realKek,
                nonce = newRealNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = realEnv.envelopeVersion,
                params = realParams,
                payload = updatedRealPayload
            )
            Arrays.fill(realKek, 0.toByte())
            realPayload.wipe()

            val updatedRealEnv = realEnv.copy(
                envelopeNonce = newRealNonce,
                wrappedPayloadCiphertext = newWrappedReal
            )

            // Sealed dummy envelope for Slot 1
            val dummyKek = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
            val dummyDek = CryptoEngine.generateDek()
            val dummyNonce = CryptoEngine.generateNonce()
            val dummyParams = CryptoEngine.KdfParams()
            val now = System.currentTimeMillis()

            val dummyPayload = CryptoEngine.UnwrappedEnvelopePayload(
                slotRoleFlag = CryptoEngine.SLOT_ROLE_DUMMY,
                decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
                createdAtEpochMs = now,
                rotatedAtEpochMs = now,
                dekBytes = dummyDek
            )
            val wrappedDecoy = CryptoEngine.wrapEnvelopePayload(
                kekBytes = dummyKek,
                nonce = dummyNonce,
                slotId = VaultSessionManager.DECOY_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                params = dummyParams,
                payload = dummyPayload
            )

            val updatedDecoyEnv = decoyEnv.copy(
                envelopeNonce = dummyNonce,
                wrappedPayloadCiphertext = wrappedDecoy
            )

            dao.disableDecoyAtomically(
                updatedRealEnvelope = updatedRealEnv,
                dummyDecoyEnvelope = updatedDecoyEnv,
                decoySlotId = VaultSessionManager.DECOY_SLOT
            )
            true
        }
    }

    /**
     * Rotates the Master Password for Slot 0 (Real Vault) non-destructively,
     * re-wrapping the existing DEK so existing encrypted records remain valid.
     */
    suspend fun rotateMasterPassword(
        currentPasswordChars: CharArray,
        newPasswordChars: CharArray
    ): Boolean = withContext(Dispatchers.IO) {
        kdfMutex.withLock {
            require(newPasswordChars.isNotEmpty()) { "New master password cannot be empty." }
            val realEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT) ?: return@withLock false
            val realParams = CryptoEngine.KdfParams(
                algorithm = realEnv.kdfAlgorithm,
                memoryKb = realEnv.kdfMemoryKb,
                iterations = realEnv.kdfIterations,
                parallelism = realEnv.kdfParallelism
            )
            val currentKek = CryptoEngine.deriveKeyArgon2id(currentPasswordChars, realEnv.kdfSalt, realParams)
            val payload = CryptoEngine.unwrapEnvelopePayloadOrNull(
                kekBytes = currentKek,
                nonce = realEnv.envelopeNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = realEnv.envelopeVersion,
                params = realParams,
                wrappedCiphertext = realEnv.wrappedPayloadCiphertext
            )
            Arrays.fill(currentKek, 0.toByte())
            if (payload == null) {
                return@withLock false
            }

            val newSalt = CryptoEngine.generateSalt()
            val newNonce = CryptoEngine.generateNonce()
            val newParams = CryptoEngine.KdfParams()
            val newKek = CryptoEngine.deriveKeyArgon2id(newPasswordChars, newSalt, newParams)

            val updatedPayload = payload.copy(rotatedAtEpochMs = System.currentTimeMillis())
            val newWrapped = CryptoEngine.wrapEnvelopePayload(
                kekBytes = newKek,
                nonce = newNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                params = newParams,
                payload = updatedPayload
            )
            Arrays.fill(newKek, 0.toByte())
            payload.wipe()

            val updatedRealEnv = realEnv.copy(
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                kdfAlgorithm = newParams.algorithm,
                kdfSalt = newSalt,
                kdfMemoryKb = newParams.memoryKb,
                kdfIterations = newParams.iterations,
                kdfParallelism = newParams.parallelism,
                envelopeNonce = newNonce,
                wrappedPayloadCiphertext = newWrapped,
                biometricNonce = null,
                biometricWrappedDek = null
            )
            BiometricKeystoreManager.deleteBiometricKey()
            dao.upsertEnvelope(updatedRealEnv)
            true
        }
    }

    // ============================================================================================
    // Encrypted CRUD Operations
    // ============================================================================================

    data class LoadedVaultItems(
        val items: List<VaultItem>,
        val corruptedCount: Int = 0
    )

    suspend fun getAllItemsForActiveSlotWithAudit(): LoadedVaultItems = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val records = dao.getRecordsForSlot(session.slotId)
        val dek = session.borrowDekCopy()
        val items = mutableListOf<VaultItem>()
        var corrupted = 0
        try {
            for (record in records) {
                try {
                    val aad = CryptoEngine.buildRecordAad(session.slotId, record.recordId, record.schemaVersion)
                    val plaintext = CryptoEngine.decryptAesGcm(
                        keyBytes = dek,
                        nonce = record.nonce,
                        ciphertext = record.ciphertext,
                        aad = aad
                    )
                    items.add(VaultRecordCodec.decode(plaintext))
                } catch (e: Exception) {
                    corrupted++
                    com.tpeditz.privatevault.v2.security.SafeVaultLogger.w(
                        "VaultRepo",
                        "Record ${record.recordId} failed decryption/decoding in slot ${session.slotId}"
                    )
                }
            }
        } finally {
            Arrays.fill(dek, 0.toByte())
        }
        LoadedVaultItems(items = items, corruptedCount = corrupted)
    }

    suspend fun getAllItemsForActiveSlot(): List<VaultItem> = withContext(Dispatchers.IO) {
        getAllItemsForActiveSlotWithAudit().items
    }

    suspend fun saveItem(item: VaultItem): Unit = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val dek = session.borrowDekCopy()
        try {
            // Check if password changed on credential to record encrypted password history
            var itemToSave = item
            if (item is CredentialRecord) {
                val existingRecord = dao.getRecordById(session.slotId, item.id)
                if (existingRecord != null) {
                    val aad = CryptoEngine.buildRecordAad(session.slotId, existingRecord.recordId, existingRecord.schemaVersion)
                    val oldPlain = runCatching {
                        CryptoEngine.decryptAesGcm(dek, existingRecord.nonce, existingRecord.ciphertext, aad)
                    }.getOrNull()
                    if (oldPlain != null) {
                        val oldCred = VaultRecordCodec.decode(oldPlain) as? CredentialRecord
                        if (oldCred != null && oldCred.password != item.password && oldCred.password.isNotBlank()) {
                            val newHist = oldCred.passwordHistory.toMutableList()
                            newHist.add(0, PasswordHistoryEntry(password = oldCred.password))
                            // Cap history to 10 entries
                            val cappedHist = if (newHist.size > 10) newHist.subList(0, 10) else newHist
                            itemToSave = item.copy(passwordHistory = cappedHist)
                        }
                    }
                }
            }

            val encoded = VaultRecordCodec.encode(itemToSave)
            val nonce = CryptoEngine.generateNonce()
            val aad = CryptoEngine.buildRecordAad(session.slotId, itemToSave.id)
            val ciphertext = CryptoEngine.encryptAesGcm(
                keyBytes = dek,
                nonce = nonce,
                plaintext = encoded,
                aad = aad
            )
            val entity = EncryptedRecordEntity(
                recordId = itemToSave.id,
                vaultSlot = session.slotId,
                schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                nonce = nonce,
                ciphertext = ciphertext
            )
            dao.upsertRecord(entity)
        } finally {
            Arrays.fill(dek, 0.toByte())
        }
    }

    suspend fun deleteItem(itemId: String): Unit = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        // If media, delete DB records atomically first, then delete physical files
        val mediaEntity = dao.getMediaFileEntity(session.slotId, itemId)
        if (mediaEntity != null) {
            dao.deleteMediaRecordAndEntityAtomically(session.slotId, itemId)
            val mediaFile = File(filesDir, mediaEntity.relativeMediaPath)
            val thumbFile = File(filesDir, mediaEntity.relativeThumbPath)
            runCatching { mediaFile.delete() }
            runCatching { thumbFile.delete() }
            return@withContext
        }

        // If document, delete DB records atomically first, then delete physical file
        val docEntity = dao.getDocumentFileEntity(session.slotId, itemId)
        if (docEntity != null) {
            dao.deleteDocumentRecordAndEntityAtomically(session.slotId, itemId)
            val docFile = File(filesDir, docEntity.relativeFilePath)
            runCatching { docFile.delete() }
            return@withContext
        }

        dao.deleteRecord(session.slotId, itemId)
    }

    /**
     * Imports visual media (photo or video) from Storage Access Framework Uri, encrypting chunks with
     * authenticated AES-256-GCM and verifying every single chunk before committing to database.
     */
    suspend fun importVisualMediaFromUri(
        uri: Uri,
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): MediaRecord = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val slotId = session.slotId

        var fileName = "media_${System.currentTimeMillis()}"
        var fileSize = -1L
        val contentResolver = context?.contentResolver ?: throw IllegalStateException("Context required for contentResolver access")
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val resolvedMime = contentResolver.getType(uri).orEmpty()
        val isVideo = resolvedMime.startsWith("video/") ||
            fileName.endsWith(".mp4", ignoreCase = true) ||
            fileName.endsWith(".mkv", ignoreCase = true) ||
            fileName.endsWith(".mov", ignoreCase = true) ||
            fileName.endsWith(".webm", ignoreCase = true)

        val mediaKind = if (isVideo) MediaKind.VIDEO else MediaKind.PHOTO
        val mimeType = resolvedMime.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }

        if (fileSize <= 0L) {
            val count = contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(65536)
                var total = 0L
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    total += read
                }
                total
            } ?: 0L
            fileSize = count
        }
        require(fileSize > 0L) { "Cannot import empty (0-byte) media file." }

        val mediaId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fileMediaKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val thumbnailKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)

        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val thumbsDir = File(filesDir, "thumbs").apply { mkdirs() }

        val relativeMediaPath = "media/$mediaId.pvmedia"
        val relativeThumbPath = "thumbs/$mediaId.pvthumb"

        val tempMediaFile = File(mediaDir, "$mediaId.tmp")
        val tempThumbFile = File(thumbsDir, "$mediaId.tmp")
        val finalMediaFile = File(filesDir, relativeMediaPath)
        val finalThumbFile = File(filesDir, relativeThumbPath)

        var widthPx = 0
        var heightPx = 0
        var durationMs = 0L

        // Generate downsampled thumbnail in memory
        val thumbBytes = ByteArrayOutputStream().use { baos ->
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    context?.let { retriever.setDataSource(it, uri) }
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = retriever.getFrameAtTime(0)
                    if (frame != null) {
                        widthPx = frame.width
                        heightPx = frame.height
                        val scaled = Bitmap.createScaledBitmap(frame, 160, 160, true)
                        scaled.compress(Bitmap.CompressFormat.PNG, 85, baos)
                    }
                } catch (e: Exception) {
                    SafeVaultLogger.w("MediaImport", "Failed video thumbnail extract: ${e.message}")
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, opts)
                        widthPx = opts.outWidth
                        heightPx = opts.outHeight
                    }
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply {
                            var sample = 1
                            while (widthPx / sample > 320 || heightPx / sample > 320) {
                                sample *= 2
                            }
                            inSampleSize = sample
                        }
                        val bitmap = BitmapFactory.decodeStream(stream, null, opts)
                        bitmap?.compress(Bitmap.CompressFormat.PNG, 85, baos)
                    }
                } catch (e: Exception) {
                    SafeVaultLogger.w("MediaImport", "Failed photo thumbnail extract: ${e.message}")
                }
            }
            val out = baos.toByteArray()
            if (out.isNotEmpty()) out else {
                val fallbackBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                val fbBaos = ByteArrayOutputStream()
                fallbackBmp.compress(Bitmap.CompressFormat.PNG, 50, fbBaos)
                fbBaos.toByteArray()
            }
        }

        var committed = false
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                StreamingMediaCryptoEngine.encryptStreamToMediaFile(
                    inputStream = inputStream,
                    expectedPlaintextSize = fileSize,
                    targetFile = tempMediaFile,
                    mediaId = mediaId,
                    vaultSlot = slotId,
                    fileMediaKey = fileMediaKey,
                    onProgress = onProgress
                )
            } ?: throw IllegalStateException("Could not open input stream for $uri")

            val encThumb = StreamingMediaCryptoEngine.encryptThumbnail(
                thumbnailPngBytes = thumbBytes,
                mediaId = mediaId,
                vaultSlot = slotId,
                thumbKey = thumbnailKey
            )
            FileOutputStream(tempThumbFile).use { it.write(encThumb) }

            val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                encryptedFile = tempMediaFile,
                mediaId = mediaId,
                vaultSlot = slotId,
                fileMediaKey = fileMediaKey
            )

            if (finalMediaFile.exists()) finalMediaFile.delete()
            if (finalThumbFile.exists()) finalThumbFile.delete()
            check(tempMediaFile.renameTo(finalMediaFile)) { "Failed to move verified media file" }
            check(tempThumbFile.renameTo(finalThumbFile)) { "Failed to move verified thumbnail file" }

            val mediaRecord = MediaRecord(
                id = mediaId,
                title = fileName,
                originalFileName = fileName,
                mimeType = mimeType,
                mediaKind = mediaKind,
                originalSizeBytes = report.header.totalPlaintextBytes,
                encryptedSizeBytes = report.encryptedFileBytes,
                chunkCount = report.header.totalChunks,
                formatVersion = report.header.formatVersion,
                widthPx = widthPx,
                heightPx = heightPx,
                durationMs = durationMs,
                fileMediaKeyBase64 = Base64.toBase64String(fileMediaKey),
                thumbnailKeyBase64 = Base64.toBase64String(thumbnailKey),
                ciphertextSha256Hex = report.ciphertextSha256Hex,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )

            val committedEntity = EncryptedMediaFileEntity(
                vaultSlot = slotId,
                mediaId = mediaId,
                formatVersion = report.header.formatVersion,
                relativeMediaPath = relativeMediaPath,
                relativeThumbPath = relativeThumbPath,
                encryptedSizeBytes = report.encryptedFileBytes,
                chunkCount = report.header.totalChunks,
                ciphertextSha256 = report.ciphertextSha256Hex,
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = now
            )

            val dek = session.borrowDekCopy()
            try {
                val recordAad = CryptoEngine.buildRecordAad(slotId, mediaId, CryptoEngine.RECORD_SCHEMA_VERSION)
                val recordBytes = VaultRecordCodec.encode(mediaRecord)
                val nonce = CryptoEngine.generateNonce()
                val cipher = CryptoEngine.encryptAesGcm(dek, nonce, recordBytes, recordAad)
                val recordEntity = EncryptedRecordEntity(
                    vaultSlot = slotId,
                    recordId = mediaId,
                    schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                    nonce = nonce,
                    ciphertext = cipher
                )
                dao.commitVerifiedMediaAtomically(recordEntity, committedEntity)
                committed = true
            } finally {
                Arrays.fill(dek, 0.toByte())
            }

            mediaRecord
        } finally {
            Arrays.fill(fileMediaKey, 0.toByte())
            Arrays.fill(thumbnailKey, 0.toByte())
            if (!committed) {
                tempMediaFile.delete()
                tempThumbFile.delete()
                finalMediaFile.delete()
                finalThumbFile.delete()
                dao.deleteMediaFileEntity(slotId, mediaId)
            }
        }
    }

    /**
     * Imports an encrypted document from Storage Access Framework Uri, chunked with AES-256-GCM.
     */
    suspend fun importDocumentFromUri(
        uri: Uri,
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): DocumentRecord = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val slotId = session.slotId

        var fileName = "document_${System.currentTimeMillis()}"
        var fileSize = -1L
        val contentResolver = context?.contentResolver ?: throw IllegalStateException("Context required for contentResolver access")
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val mimeType = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }

        if (fileSize <= 0L) {
            val count = contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(65536)
                var total = 0L
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    total += read
                }
                total
            } ?: 0L
            fileSize = count
        }
        require(fileSize > 0L) { "Cannot import empty document." }

        val docId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fileKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)

        val docDir = File(filesDir, "documents").apply { mkdirs() }
        val relativeDocPath = "documents/$docId.pvdoc"

        val tempDocFile = File(docDir, "$docId.tmp")
        val finalDocFile = File(filesDir, relativeDocPath)

        var committed = false
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                StreamingMediaCryptoEngine.encryptStreamToMediaFile(
                    inputStream = inputStream,
                    expectedPlaintextSize = fileSize,
                    targetFile = tempDocFile,
                    mediaId = docId,
                    vaultSlot = slotId,
                    fileMediaKey = fileKey,
                    onProgress = onProgress
                )
            } ?: throw IllegalStateException("Could not open input stream for $uri")

            val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                encryptedFile = tempDocFile,
                mediaId = docId,
                vaultSlot = slotId,
                fileMediaKey = fileKey
            )

            if (finalDocFile.exists()) finalDocFile.delete()
            check(tempDocFile.renameTo(finalDocFile)) { "Failed to move verified document file" }

            val docRecord = DocumentRecord(
                id = docId,
                title = fileName,
                originalFileName = fileName,
                mimeType = mimeType,
                originalSizeBytes = report.header.totalPlaintextBytes,
                encryptedSizeBytes = report.encryptedFileBytes,
                chunkCount = report.header.totalChunks,
                formatVersion = report.header.formatVersion,
                fileKeyBase64 = Base64.toBase64String(fileKey),
                ciphertextSha256Hex = report.ciphertextSha256Hex,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )

            val committedEntity = EncryptedDocumentFileEntity(
                vaultSlot = slotId,
                documentId = docId,
                formatVersion = report.header.formatVersion,
                relativeFilePath = relativeDocPath,
                encryptedSizeBytes = report.encryptedFileBytes,
                chunkCount = report.header.totalChunks,
                ciphertextSha256 = report.ciphertextSha256Hex,
                importStatus = EncryptedDocumentFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = now
            )

            val dek = session.borrowDekCopy()
            try {
                val recordAad = CryptoEngine.buildRecordAad(slotId, docId, CryptoEngine.RECORD_SCHEMA_VERSION)
                val recordBytes = VaultRecordCodec.encode(docRecord)
                val nonce = CryptoEngine.generateNonce()
                val cipher = CryptoEngine.encryptAesGcm(dek, nonce, recordBytes, recordAad)
                val recordEntity = EncryptedRecordEntity(
                    vaultSlot = slotId,
                    recordId = docId,
                    schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                    nonce = nonce,
                    ciphertext = cipher
                )
                dao.commitVerifiedDocumentAtomically(recordEntity, committedEntity)
                committed = true
            } finally {
                Arrays.fill(dek, 0.toByte())
            }

            docRecord
        } finally {
            Arrays.fill(fileKey, 0.toByte())
            if (!committed) {
                tempDocFile.delete()
                finalDocFile.delete()
                dao.deleteDocumentFileEntity(slotId, docId)
            }
        }
    }

    suspend fun loadDecryptedPhotoBytes(mediaRecord: MediaRecord): ByteArray = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val mediaEntity = dao.getMediaFileEntity(session.slotId, mediaRecord.id)
        val mediaFile = if (mediaEntity != null) {
            File(filesDir, mediaEntity.relativeMediaPath)
        } else {
            File(filesDir, "media/${mediaRecord.id}.pvmedia").let {
                if (it.exists()) it else File(filesDir, "encrypted_media_${mediaRecord.id}.pvm")
            }
        }
        require(mediaFile.exists()) { "Encrypted media file not found on disk." }
        val fileMediaKey = Base64.decode(mediaRecord.fileMediaKeyBase64)
        try {
            StreamingMediaCryptoEngine.decryptEntireMediaFile(
                encryptedFile = mediaFile,
                mediaId = mediaRecord.id,
                vaultSlot = session.slotId,
                fileMediaKey = fileMediaKey
            )
        } finally {
            Arrays.fill(fileMediaKey, 0.toByte())
        }
    }

    suspend fun openEncryptedVideoDataSource(
        mediaRecord: MediaRecord
    ): StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val mediaEntity = dao.getMediaFileEntity(session.slotId, mediaRecord.id)
        val mediaFile = if (mediaEntity != null) {
            File(filesDir, mediaEntity.relativeMediaPath)
        } else {
            File(filesDir, "media/${mediaRecord.id}.pvmedia")
        }
        require(mediaFile.exists()) { "Encrypted video file not found." }
        val fileMediaKey = Base64.decode(mediaRecord.fileMediaKeyBase64)
        StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource(
            mediaFile = mediaFile,
            mediaId = mediaRecord.id,
            vaultSlot = session.slotId,
            fileMediaKey = fileMediaKey
        )
    }

    suspend fun loadDecryptedThumbnailBytes(mediaRecord: MediaRecord): ByteArray = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val mediaEntity = dao.getMediaFileEntity(session.slotId, mediaRecord.id)
        val thumbFile = if (mediaEntity != null) {
            File(filesDir, mediaEntity.relativeThumbPath)
        } else {
            File(filesDir, "thumbs/${mediaRecord.id}.pvthumb")
        }
        require(thumbFile.exists()) { "Encrypted thumbnail file not found on disk." }
        val thumbKey = Base64.decode(mediaRecord.thumbnailKeyBase64)
        try {
            val raw = thumbFile.readBytes()
            val magic = "PVM1".toByteArray(StandardCharsets.US_ASCII)
            if (raw.size >= 32 && raw.copyOfRange(0, 4).contentEquals(magic)) {
                StreamingMediaCryptoEngine.decryptEntireMediaFile(
                    encryptedFile = thumbFile,
                    mediaId = mediaRecord.id,
                    vaultSlot = session.slotId,
                    fileMediaKey = thumbKey
                )
            } else {
                StreamingMediaCryptoEngine.decryptThumbnail(
                    encryptedThumbBytes = raw,
                    mediaId = mediaRecord.id,
                    vaultSlot = session.slotId,
                    thumbKey = thumbKey
                )
            }
        } finally {
            Arrays.fill(thumbKey, 0.toByte())
        }
    }

    suspend fun loadDecryptedDocumentBytes(documentRecord: DocumentRecord): ByteArray = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        val docEntity = dao.getDocumentFileEntity(session.slotId, documentRecord.id)
        val docFile = if (docEntity != null) {
            File(filesDir, docEntity.relativeFilePath)
        } else {
            File(filesDir, "documents/${documentRecord.id}.pvdoc")
        }
        require(docFile.exists()) { "Encrypted document file not found on disk." }
        val fileKey = Base64.decode(documentRecord.fileKeyBase64)
        try {
            StreamingMediaCryptoEngine.decryptEntireMediaFile(
                encryptedFile = docFile,
                mediaId = documentRecord.id,
                vaultSlot = session.slotId,
                fileMediaKey = fileKey
            )
        } finally {
            Arrays.fill(fileKey, 0.toByte())
        }
    }

    /**
     * Exports a streaming encrypted V2 backup bound to the currently unlocked vault session,
     * resolving authoritative database asset paths so restored/migrated assets are always located accurately.
     */
    suspend fun createAuthoritativeStreamingBackup(
        items: List<VaultItem>,
        backupPasswordChars: CharArray,
        outputStream: OutputStream,
        appVersionName: String = "2.0.0",
        kdfParams: CryptoEngine.KdfParams = CryptoEngine.KdfParams(),
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        vaultMutationMutex.withLock {
            val session = sessionManager.requireUnlockedSession()
            val initialSessionId = session.sessionId
            val slotId = session.slotId
            require(!session.isWiped) { "Vault session is invalid or wiped." }

            val mediaEntities = dao.getAllMediaFileEntitiesForSlot(slotId).associateBy { it.mediaId }
            val docEntities = dao.getAllDocumentFileEntitiesForSlot(slotId).associateBy { it.documentId }

            val resolvedMap = HashMap<String, Pair<String?, String?>>(mediaEntities.size + docEntities.size)
            for ((id, m) in mediaEntities) {
                resolvedMap[id] = m.relativeMediaPath to m.relativeThumbPath
            }
            for ((id, d) in docEntities) {
                resolvedMap[id] = d.relativeFilePath to null
            }

            EncryptedBackupV2Engine.createStreamingBackup(
                items = items,
                backupPasswordChars = backupPasswordChars,
                outputStream = outputStream,
                appFilesDir = filesDir,
                appVersionName = appVersionName,
                kdfParams = kdfParams,
                assetPathResolver = { id -> resolvedMap[id] ?: (null to null) },
                isCancelled = {
                    val active = sessionManager.getActiveSession()
                    isCancelled() || active == null || active.sessionId != initialSessionId || active.isWiped
                },
                onProgress = onProgress
            )
        }
    }

    /**
     * Imports credentials from a user-supplied CSV file via Storage Access Framework, adhering strictly
     * to RFC 4180 parsing with multiline quoted strings and without destructive formula stripping.
     */
    suspend fun importCredentialsFromCsv(inputStream: InputStream): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val session = sessionManager.requireUnlockedSession()
        var importedCount = 0
        var skippedCount = 0

        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            val rows = parseRfc4180Csv(reader)
            if (rows.isEmpty()) return@withContext Pair(0, 0)

            val headers = rows.first()
            var titleIdx = -1
            var userIdx = -1
            var pwdIdx = -1
            var urlIdx = -1
            var notesIdx = -1
            var folderIdx = -1

            headers.forEachIndexed { index, rawHeader ->
                val h = rawHeader.trim().lowercase()
                when {
                    h.contains("title") || h.contains("service") || h.contains("name") -> if (titleIdx == -1) titleIdx = index
                    h.contains("username") || h.contains("login") || h.contains("email") || h.contains("user") -> if (userIdx == -1) userIdx = index
                    h.contains("password") || h.contains("pass") -> if (pwdIdx == -1) pwdIdx = index
                    h.contains("url") || h.contains("website") || h.contains("uri") -> if (urlIdx == -1) urlIdx = index
                    h.contains("note") || h.contains("comment") -> if (notesIdx == -1) notesIdx = index
                    h.contains("folder") || h.contains("category") -> if (folderIdx == -1) folderIdx = index
                }
            }

            if (pwdIdx == -1 && userIdx == -1) {
                // Invalid or unsupported CSV header
                return@withContext Pair(0, 0)
            }

            for (rowIndex in 1 until rows.size) {
                val row = rows[rowIndex]
                if (row.isEmpty() || row.all { it.isBlank() }) continue

                val title = if (titleIdx in row.indices) row[titleIdx].trim() else "Imported Credential"
                val username = if (userIdx in row.indices) row[userIdx].trim() else ""
                val password = if (pwdIdx in row.indices) row[pwdIdx] else ""
                val url = if (urlIdx in row.indices) row[urlIdx].trim() else ""
                val notes = if (notesIdx in row.indices) row[notesIdx] else ""
                val folderName = if (folderIdx in row.indices) row[folderIdx].trim().ifBlank { VaultFolder.DEFAULT_FOLDER_NAME } else VaultFolder.DEFAULT_FOLDER_NAME

                if (password.isBlank() && username.isBlank()) {
                    skippedCount++
                    continue
                }

                val cred = CredentialRecord(
                    serviceName = title.ifBlank { "Credential" },
                    username = username,
                    password = password,
                    websiteUrl = url,
                    notes = notes,
                    folderName = folderName
                )
                saveItem(cred)
                importedCount++
            }
        }
        Pair(importedCount, skippedCount)
    }

    private fun parseRfc4180Csv(reader: Reader): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRecord = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false

        while (true) {
            val ch = reader.read()
            if (ch == -1) break
            val c = ch.toChar()

            if (inQuotes) {
                if (c == '\"') {
                    reader.mark(1)
                    val nextCh = reader.read()
                    if (nextCh == '\"'.code) {
                        currentField.append('\"')
                    } else {
                        inQuotes = false
                        if (nextCh != -1) reader.reset()
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '\"' -> {
                        inQuotes = true
                    }
                    ',' -> {
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        reader.mark(1)
                        val nextCh = reader.read()
                        if (nextCh != '\n'.code && nextCh != -1) {
                            reader.reset()
                        }
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                        if (currentRecord.isNotEmpty()) {
                            records.add(currentRecord.toList())
                            currentRecord.clear()
                        }
                    }
                    '\n' -> {
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                        if (currentRecord.isNotEmpty()) {
                            records.add(currentRecord.toList())
                            currentRecord.clear()
                        }
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
            }
        }

        if (currentField.isNotEmpty() || currentRecord.isNotEmpty()) {
            currentRecord.add(currentField.toString())
            records.add(currentRecord.toList())
        }
        return records
    }

    data class RestoreReport(
        val restoredItemsCount: Int,
        val restoredMediaCount: Int,
        val restoredDocumentCount: Int
    )

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val db = database
        return when {
            db != null -> db.withTransaction { block() }
            dao is TransactionalVaultDao -> dao.withTransaction(block)
            else -> block()
        }
    }

    private fun copyStreamWithSync(source: File, target: File) {
        target.parentFile?.mkdirs()
        val buf = ByteArray(64 * 1024)
        source.inputStream().use { input ->
            FileOutputStream(target).use { fos ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                }
                fos.flush()
                fos.fd.sync()
            }
        }
    }

    private fun computeFileSha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeRecoveryJournal(
        recoveryJournalDir: File,
        journalFile: File,
        slotId: Int,
        mode: RestoreMode,
        state: String,
        stagingDir: File? = null,
        writtenAssetFiles: List<File> = emptyList(),
        stagedFilesToPromote: List<Pair<File, File>> = emptyList(),
        filesToDeleteAfterCommit: List<File> = emptyList(),
        backedUpFiles: List<Pair<File, File>> = emptyList(),
        newlyCopiedFiles: List<File> = emptyList()
    ) {
        recoveryJournalDir.mkdirs()
        val json = JSONObject().apply {
            put("slotId", slotId)
            put("mode", mode.name)
            put("state", state)
            put("timestamp", System.currentTimeMillis())
            if (stagingDir != null) {
                put("stagingDir", stagingDir.absolutePath)
            }

            val writtenArr = JSONArray()
            for (f in writtenAssetFiles) {
                writtenArr.put(f.absolutePath)
            }
            put("writtenAssetFiles", writtenArr)

            val stagedArr = JSONArray()
            for ((staged, target) in stagedFilesToPromote) {
                stagedArr.put(JSONObject().apply {
                    put("staged", staged.absolutePath)
                    put("target", target.absolutePath)
                })
            }
            put("stagedFilesToPromote", stagedArr)

            val toDeleteArr = JSONArray()
            for (f in filesToDeleteAfterCommit) {
                toDeleteArr.put(f.absolutePath)
            }
            put("filesToDeleteAfterCommit", toDeleteArr)

            val backedUpArr = JSONArray()
            for ((orig, backup) in backedUpFiles) {
                backedUpArr.put(JSONObject().apply {
                    put("original", orig.absolutePath)
                    put("backup", backup.absolutePath)
                })
            }
            put("backedUpFiles", backedUpArr)

            val newlyCopiedArr = JSONArray()
            for (f in newlyCopiedFiles) {
                newlyCopiedArr.put(f.absolutePath)
            }
            put("newlyCopiedFiles", newlyCopiedArr)
        }

        val tempFile = File(recoveryJournalDir, "restore_manifest.tmp")
        FileOutputStream(tempFile).use { fos ->
            val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (tempFile.exists()) {
            if (journalFile.exists()) journalFile.delete()
            tempFile.renameTo(journalFile)
        }
    }

    /**
     * Authoritative startup recovery:
     * Queries the actual database (`dao.getAllMediaFileEntities()`, `dao.getAllDocumentFileEntities()`)
     * to distinguish committed assets from uncommitted/obsolete assets before deleting any files.
     * Never deletes any file currently referenced by any vault slot in the database.
     */
    fun recoverIncompleteRestoreIfPresent(): Boolean {
        val recoveryJournalDir = File(filesDir, RECOVERY_JOURNAL_DIR_NAME)
        if (!recoveryJournalDir.exists()) return false
        val journalFile = File(recoveryJournalDir, RECOVERY_MANIFEST_FILE_NAME)
        if (!journalFile.exists()) {
            recoveryJournalDir.deleteRecursively()
            return false
        }
        return executeRecoveryRollback(recoveryJournalDir, journalFile)
    }

    private fun getAuthoritativeReferencedCanonicalPaths(): Set<String> {
        return runBlocking(Dispatchers.IO) {
            val mediaPaths = dao.getAllMediaFileEntities().flatMap {
                listOf(File(filesDir, it.relativeMediaPath), File(filesDir, it.relativeThumbPath))
            }
            val docPaths = dao.getAllDocumentFileEntities().map {
                File(filesDir, it.relativeFilePath)
            }
            (mediaPaths + docPaths).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        }
    }

    private fun executeRecoveryRollback(recoveryJournalDir: File, journalFile: File): Boolean {
        var allCleanupSucceeded = true
        try {
            if (!journalFile.exists()) {
                recoveryJournalDir.deleteRecursively()
                return false
            }
            val json = JSONObject(journalFile.readText(StandardCharsets.UTF_8))
            val state = json.optString("state", STATE_PREPARING)
            val stagingDirStr = json.optString("stagingDir")
            val stagingDir = if (stagingDirStr.isNotBlank()) File(stagingDirStr) else null

            val authoritativeReferencedPaths = runCatching { getAuthoritativeReferencedCanonicalPaths() }.getOrNull()
                ?: emptySet()

            // Handle unique durable writtenAssetFiles:
            // Any file written for a restore that is NOT referenced in the authoritative DB was uncommitted -> delete it.
            // Any file that IS referenced in the authoritative DB was committed -> NEVER delete it!
            val writtenArr = json.optJSONArray("writtenAssetFiles")
            if (writtenArr != null) {
                for (i in 0 until writtenArr.length()) {
                    val path = writtenArr.optString(i)
                    if (path.isNotBlank()) {
                        val f = File(path)
                        val canon = runCatching { f.canonicalPath }.getOrNull()
                        if (canon != null && !authoritativeReferencedPaths.contains(canon) && f.exists()) {
                            if (!f.delete()) allCleanupSucceeded = false
                        }
                    }
                }
            }

            if (state == STATE_COMMITTED) {
                // Backward compatibility: if an older staged recovery manifest was committed, promote remaining staged files
                val stagedArr = json.optJSONArray("stagedFilesToPromote")
                val promotedPaths = mutableSetOf<String>()
                if (stagedArr != null) {
                    for (i in 0 until stagedArr.length()) {
                        val pairObj = stagedArr.optJSONObject(i) ?: continue
                        val stagedFile = File(pairObj.getString("staged"))
                        val targetFile = File(pairObj.getString("target"))
                        if (stagedFile.exists()) {
                            targetFile.parentFile?.mkdirs()
                            stagedFile.copyTo(targetFile, overwrite = true)
                            stagedFile.delete()
                        }
                        if (targetFile.exists()) {
                            promotedPaths.add(targetFile.canonicalPath)
                        }
                    }
                }

                // Clean up obsolete replaced files only if they are NOT referenced by any DB entity or promoted path
                val toDeleteArr = json.optJSONArray("filesToDeleteAfterCommit")
                if (toDeleteArr != null) {
                    for (i in 0 until toDeleteArr.length()) {
                        val path = toDeleteArr.optString(i)
                        if (path.isNotBlank()) {
                            val f = File(path)
                            val canon = runCatching { f.canonicalPath }.getOrNull()
                            if (f.exists() && canon != null && !promotedPaths.contains(canon) && !authoritativeReferencedPaths.contains(canon)) {
                                if (!f.delete()) allCleanupSucceeded = false
                            }
                        }
                    }
                }
            } else {
                // Uncommitted restore: clean up temporary staging directory and legacy rollback buffers
                stagingDir?.let { runCatching { it.deleteRecursively() } }

                val newlyCopiedArr = json.optJSONArray("newlyCopiedFiles")
                if (newlyCopiedArr != null) {
                    for (i in 0 until newlyCopiedArr.length()) {
                        val path = newlyCopiedArr.optString(i)
                        if (path.isNotBlank()) {
                            val f = File(path)
                            val canon = runCatching { f.canonicalPath }.getOrNull()
                            if (f.exists() && (canon == null || !authoritativeReferencedPaths.contains(canon))) {
                                runCatching { f.delete() }
                            }
                        }
                    }
                }

                val backedUpArr = json.optJSONArray("backedUpFiles")
                if (backedUpArr != null) {
                    for (i in 0 until backedUpArr.length()) {
                        val pairObj = backedUpArr.optJSONObject(i) ?: continue
                        val origFile = File(pairObj.getString("original"))
                        val backupFile = File(pairObj.getString("backup"))
                        if (backupFile.exists()) {
                            runCatching {
                                origFile.parentFile?.mkdirs()
                                backupFile.copyTo(origFile, overwrite = true)
                                backupFile.delete()
                            }
                        }
                    }
                }
            }
            if (allCleanupSucceeded) {
                recoveryJournalDir.deleteRecursively()
            }
            return true
        } catch (e: Exception) {
            SafeVaultLogger.e("VaultRepo", "Recovery rollback execution failed", e)
            return false
        }
    }

    suspend fun commitRestore(
        staged: StagedBackupRestore,
        mode: RestoreMode,
        isCancelled: () -> Boolean = { false }
    ): RestoreReport = withContext(Dispatchers.IO) {
        vaultMutationMutex.withLock {
            if (isCancelled()) throw CancellationException("Restore cancelled before execution.")

            val session = sessionManager.requireUnlockedSession()
            val initialSessionId = session.sessionId
            val slotId = session.slotId
            require(!session.isWiped) { "Vault operation rejected: Vault session is wiped or invalid." }
            val dek = session.borrowDekCopy()

            val mediaDir = File(filesDir, "media").apply { mkdirs() }
            val thumbDir = File(filesDir, "thumbs").apply { mkdirs() }
            val docDir = File(filesDir, "documents").apply { mkdirs() }
            val filesDirCanonical = filesDir.canonicalPath

            val stagedMediaDir = File(staged.stagingDir, "media")
            val stagedThumbDir = File(staged.stagingDir, "thumbs")
            val stagedDocDir = sequenceOf(
                File(staged.stagingDir, "documents"),
                File(staged.stagingDir, "docs")
            ).firstOrNull { it.exists() } ?: File(staged.stagingDir, "docs")

            val pvm1Magic = "PVM1".toByteArray(StandardCharsets.US_ASCII)

            // 1. Strict Upfront Pre-flight Validation & Authentication of All Payloads and Keys
            val seenIds = HashSet<String>(staged.payload.items.size)
            for (item in staged.payload.items) {
                if (isCancelled()) throw CancellationException("Restore cancelled during validation.")
                require(item.id.isNotBlank()) { "Restore rejected: Encountered record with blank ID." }
                require(seenIds.add(item.id)) { "Restore rejected: Duplicate record ID ${item.id} in backup manifest." }

                if (item is MediaRecord) {
                    val stagedMediaFile = sequenceOf(
                        File(stagedMediaDir, "${item.id}.pvmedia"),
                        File(stagedMediaDir, "${item.id}.pvm")
                    ).firstOrNull { it.exists() }
                        ?: throw IllegalArgumentException("Restore rejected: Missing media payload file for record ${item.id} (${item.title}).")

                    require(stagedMediaFile.length() > 0L) {
                        "Restore rejected: Empty media payload file for ${item.id}."
                    }

                    require(item.fileMediaKeyBase64.isNotBlank() && item.thumbnailKeyBase64.isNotBlank()) {
                        "Restore rejected: Missing authentic encryption keys for media ${item.id}."
                    }
                    val mediaKey = runCatching { Base64.decode(item.fileMediaKeyBase64) }.getOrNull()
                    require(mediaKey != null && mediaKey.size == 32) {
                        "Restore rejected: Invalid 256-bit media encryption key for ${item.id}."
                    }
                    val thumbKey = runCatching { Base64.decode(item.thumbnailKeyBase64) }.getOrNull()
                    require(thumbKey != null && thumbKey.size == 32) {
                        "Restore rejected: Invalid 256-bit thumbnail encryption key for ${item.id}."
                    }

                    if (item.ciphertextSha256Hex.length == 64) {
                        val computedSha = computeFileSha256Hex(stagedMediaFile)
                        require(computedSha.equals(item.ciphertextSha256Hex, ignoreCase = true)) {
                            "Restore rejected: SHA-256 digest mismatch on media payload for ${item.id}."
                        }
                    }

                    // Authenticate encrypted PVM1 media frames via bounded streaming verification
                    val headerBytes = ByteArray(4)
                    stagedMediaFile.inputStream().use { it.read(headerBytes) }
                    require(headerBytes.contentEquals(pvm1Magic)) {
                        "Restore rejected: Media payload for ${item.id} lacks valid PVM1 encrypted header."
                    }
                    StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                        encryptedFile = stagedMediaFile,
                        mediaId = item.id,
                        vaultSlot = slotId,
                        fileMediaKey = mediaKey
                    )

                    val stagedThumbFile = sequenceOf(
                        File(stagedThumbDir, "${item.id}.pvthumb"),
                        File(stagedThumbDir, "${item.id}.pvt")
                    ).firstOrNull { it.exists() }
                    require(stagedThumbFile != null && stagedThumbFile.exists() && stagedThumbFile.length() > 0L) {
                        "Restore rejected: Missing thumbnail payload file for ${item.id}."
                    }

                    val thumbHeader = ByteArray(4)
                    stagedThumbFile.inputStream().use { it.read(thumbHeader) }
                    if (thumbHeader.contentEquals(pvm1Magic)) {
                        StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                            encryptedFile = stagedThumbFile,
                            mediaId = item.id,
                            vaultSlot = slotId,
                            fileMediaKey = thumbKey
                        )
                    } else {
                        runCatching {
                            StreamingMediaCryptoEngine.decryptThumbnail(
                                encryptedThumbBytes = stagedThumbFile.readBytes(),
                                mediaId = item.id,
                                vaultSlot = slotId,
                                thumbKey = thumbKey
                            )
                        }.onFailure {
                            throw IllegalArgumentException("Restore rejected: Thumbnail authentication failed for ${item.id}: ${it.message}", it)
                        }
                    }
                }

                if (item is DocumentRecord) {
                    val stagedDocFile = sequenceOf(
                        File(stagedDocDir, "${item.id}.pvdoc"),
                        File(stagedDocDir, "${item.id}.pvm")
                    ).firstOrNull { it.exists() }
                        ?: throw IllegalArgumentException("Restore rejected: Missing document payload file for record ${item.id} (${item.title}).")

                    require(stagedDocFile.length() > 0L) {
                        "Restore rejected: Empty document payload file for ${item.id}."
                    }

                    require(item.fileKeyBase64.isNotBlank()) {
                        "Restore rejected: Missing authentic encryption key for document ${item.id}."
                    }
                    val docKey = runCatching { Base64.decode(item.fileKeyBase64) }.getOrNull()
                    require(docKey != null && docKey.size == 32) {
                        "Restore rejected: Invalid 256-bit document encryption key for ${item.id}."
                    }

                    if (item.ciphertextSha256Hex.length == 64) {
                        val computedSha = computeFileSha256Hex(stagedDocFile)
                        require(computedSha.equals(item.ciphertextSha256Hex, ignoreCase = true)) {
                            "Restore rejected: SHA-256 digest mismatch on document payload for ${item.id}."
                        }
                    }

                    val docHeader = ByteArray(4)
                    stagedDocFile.inputStream().use { it.read(docHeader) }
                    require(docHeader.contentEquals(pvm1Magic)) {
                        "Restore rejected: Document payload for ${item.id} lacks valid PVM1 encrypted header."
                    }
                    StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                        encryptedFile = stagedDocFile,
                        mediaId = item.id,
                        vaultSlot = slotId,
                        fileMediaKey = docKey
                    )
                }
            }

            // 2. Write Validated Restored Files to Unique Durable App-Private Paths BEFORE Database Commit.
            //    This guarantees we never overwrite active vault files and never depend on post-commit file moves.
            val commitToken = UUID.randomUUID().toString().replace("-", "").take(12)
            val recoveryJournalDir = File(filesDir, RECOVERY_JOURNAL_DIR_NAME)
            val journalFile = File(recoveryJournalDir, RECOVERY_MANIFEST_FILE_NAME)

            val writtenDurableFiles = mutableListOf<File>()
            val filesToDeleteAfterCommit = mutableListOf<File>()
            val durableMediaRelPaths = mutableMapOf<String, Pair<String, String>>()
            val durableDocRelPaths = mutableMapOf<String, String>()

            try {
                val existingRecordsMap = if (mode == RestoreMode.MERGE_KEEP_NEWEST) {
                    dao.getRecordsForSlot(slotId).associateBy { it.recordId }
                } else {
                    emptyMap()
                }

                val itemsToRestore = mutableListOf<VaultItem>()
                for (item in staged.payload.items) {
                    if (mode == RestoreMode.MERGE_KEEP_NEWEST && existingRecordsMap.containsKey(item.id)) {
                        val existingEntity = existingRecordsMap[item.id]
                        if (existingEntity != null) {
                            val existingDecrypted = runCatching {
                                val itemBytes = CryptoEngine.decryptAesGcm(
                                    keyBytes = dek,
                                    nonce = existingEntity.nonce,
                                    ciphertext = existingEntity.ciphertext,
                                    aad = CryptoEngine.buildRecordAad(vaultSlot = slotId, recordId = item.id, schemaVersion = existingEntity.schemaVersion)
                                )
                                VaultRecordCodec.decode(itemBytes)
                            }.getOrNull()
                            if (existingDecrypted != null && existingDecrypted.updatedAtEpochMs >= item.updatedAtEpochMs) {
                                continue
                            }
                        }
                    }
                    itemsToRestore.add(item)
                }

                // Collect paths that will become obsolete if commit succeeds
                if (mode == RestoreMode.REPLACE_ACTIVE_VAULT) {
                    val existingMedia = dao.getAllMediaFileEntitiesForSlot(slotId)
                    val existingDocs = dao.getAllDocumentFileEntitiesForSlot(slotId)
                    for (m in existingMedia) {
                        filesToDeleteAfterCommit.add(File(filesDir, m.relativeMediaPath))
                        filesToDeleteAfterCommit.add(File(filesDir, m.relativeThumbPath))
                    }
                    for (d in existingDocs) {
                        filesToDeleteAfterCommit.add(File(filesDir, d.relativeFilePath))
                    }
                } else if (mode == RestoreMode.MERGE_KEEP_NEWEST) {
                    for (item in itemsToRestore) {
                        if (item is MediaRecord) {
                            dao.getMediaFileEntity(slotId, item.id)?.let { old ->
                                filesToDeleteAfterCommit.add(File(filesDir, old.relativeMediaPath))
                                filesToDeleteAfterCommit.add(File(filesDir, old.relativeThumbPath))
                            }
                        } else if (item is DocumentRecord) {
                            dao.getDocumentFileEntity(slotId, item.id)?.let { old ->
                                filesToDeleteAfterCommit.add(File(filesDir, old.relativeFilePath))
                            }
                        }
                    }
                }

                // Record journal BEFORE creating new files so abrupt kills can purge orphaned partial files
                writeRecoveryJournal(
                    recoveryJournalDir = recoveryJournalDir,
                    journalFile = journalFile,
                    slotId = slotId,
                    mode = mode,
                    state = STATE_PREPARING,
                    writtenAssetFiles = writtenDurableFiles,
                    filesToDeleteAfterCommit = filesToDeleteAfterCommit
                )

                for (item in itemsToRestore) {
                    if (isCancelled()) throw CancellationException("Restore cancelled during file write.")

                    if (item is MediaRecord) {
                        val stagedMediaFile = sequenceOf(
                            File(stagedMediaDir, "${item.id}.pvmedia"),
                            File(stagedMediaDir, "${item.id}.pvm")
                        ).first { it.exists() }

                        val stagedThumbFile = sequenceOf(
                            File(stagedThumbDir, "${item.id}.pvthumb"),
                            File(stagedThumbDir, "${item.id}.pvt")
                        ).first { it.exists() }

                        val relMediaPath = "media/${item.id}_$commitToken.pvmedia"
                        val relThumbPath = "thumbs/${item.id}_$commitToken.pvthumb"
                        val destMediaFile = File(filesDir, relMediaPath)
                        val destThumbFile = File(filesDir, relThumbPath)
                        require(destMediaFile.canonicalPath.startsWith(filesDirCanonical))
                        require(destThumbFile.canonicalPath.startsWith(filesDirCanonical))

                        copyStreamWithSync(stagedMediaFile, destMediaFile)
                        writtenDurableFiles.add(destMediaFile)
                        copyStreamWithSync(stagedThumbFile, destThumbFile)
                        writtenDurableFiles.add(destThumbFile)

                        durableMediaRelPaths[item.id] = relMediaPath to relThumbPath
                    }

                    if (item is DocumentRecord) {
                        val stagedDocFile = sequenceOf(
                            File(stagedDocDir, "${item.id}.pvdoc"),
                            File(stagedDocDir, "${item.id}.pvm")
                        ).first { it.exists() }

                        val relDocPath = "documents/${item.id}_$commitToken.pvdoc"
                        val destDocFile = File(filesDir, relDocPath)
                        require(destDocFile.canonicalPath.startsWith(filesDirCanonical))

                        copyStreamWithSync(stagedDocFile, destDocFile)
                        writtenDurableFiles.add(destDocFile)

                        durableDocRelPaths[item.id] = relDocPath
                    }
                }

                // Update journal with complete list of written durable files before atomic DB commit
                writeRecoveryJournal(
                    recoveryJournalDir = recoveryJournalDir,
                    journalFile = journalFile,
                    slotId = slotId,
                    mode = mode,
                    state = STATE_STAGED,
                    writtenAssetFiles = writtenDurableFiles,
                    filesToDeleteAfterCommit = filesToDeleteAfterCommit
                )

                if (isCancelled()) throw CancellationException("Restore cancelled before database commit.")

                val preDbSession = sessionManager.requireUnlockedSession()
                require(preDbSession.sessionId == initialSessionId && preDbSession.slotId == slotId && !preDbSession.isWiped) {
                    "Vault operation rejected: Session changed or was locked before database commit."
                }

                var restoredItems = 0
                var restoredMedia = 0
                var restoredDocs = 0

                // 3. Atomically Commit Records and References to Already-Completed Durable Files
                runInTransaction {
                    val activeSession = sessionManager.requireUnlockedSession()
                    require(activeSession.sessionId == initialSessionId && activeSession.slotId == slotId && !activeSession.isWiped) {
                        "Vault session invalidated during database transaction."
                    }

                    if (mode == RestoreMode.REPLACE_ACTIVE_VAULT) {
                        dao.deleteAllRecordsForSlot(slotId)
                        dao.deleteAllMediaFilesForSlot(slotId)
                        dao.deleteAllDocumentFilesForSlot(slotId)
                    }

                    for (item in itemsToRestore) {
                        val rawBytes = VaultRecordCodec.encode(item)
                        val nonce = CryptoEngine.generateNonce()
                        val aad = CryptoEngine.buildRecordAad(vaultSlot = slotId, recordId = item.id, schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION)
                        val ciphertext = CryptoEngine.encryptAesGcm(dek, nonce, rawBytes, aad)

                        val recordEntity = EncryptedRecordEntity(
                            recordId = item.id,
                            vaultSlot = slotId,
                            schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                            nonce = nonce,
                            ciphertext = ciphertext
                        )
                        dao.upsertRecord(recordEntity)
                        restoredItems++

                        if (item is MediaRecord) {
                            val (relMedia, relThumb) = durableMediaRelPaths.getValue(item.id)
                            val mediaFile = File(filesDir, relMedia)
                            val mediaEntity = EncryptedMediaFileEntity(
                                vaultSlot = slotId,
                                mediaId = item.id,
                                formatVersion = item.formatVersion,
                                relativeMediaPath = relMedia,
                                relativeThumbPath = relThumb,
                                encryptedSizeBytes = mediaFile.length(),
                                chunkCount = item.chunkCount,
                                ciphertextSha256 = item.ciphertextSha256Hex,
                                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                                createdAtEpochMs = item.createdAtEpochMs
                            )
                            dao.upsertMediaFileEntity(mediaEntity)
                            restoredMedia++
                        }

                        if (item is DocumentRecord) {
                            val relDoc = durableDocRelPaths.getValue(item.id)
                            val docFile = File(filesDir, relDoc)
                            val docEntity = EncryptedDocumentFileEntity(
                                vaultSlot = slotId,
                                documentId = item.id,
                                formatVersion = item.formatVersion,
                                relativeFilePath = relDoc,
                                encryptedSizeBytes = docFile.length(),
                                chunkCount = item.chunkCount,
                                ciphertextSha256 = item.ciphertextSha256Hex,
                                importStatus = EncryptedDocumentFileEntity.IMPORT_STATUS_COMMITTED,
                                createdAtEpochMs = item.createdAtEpochMs
                            )
                            dao.upsertDocumentFileEntity(docEntity)
                            restoredDocs++
                        }
                    }
                }

                // 4. Clean Up Unreferenced Obsolete Files After DB Commit (authoritative DB check across ALL slots)
                val authoritativePaths = getAuthoritativeReferencedCanonicalPaths()
                for (f in filesToDeleteAfterCommit) {
                    val canon = runCatching { f.canonicalPath }.getOrNull()
                    if (f.exists() && canon != null && !authoritativePaths.contains(canon)) {
                        runCatching { f.delete() }
                    }
                }

                recoveryJournalDir.deleteRecursively()
                staged.stagingDir.deleteRecursively()

                RestoreReport(restoredItems, restoredMedia, restoredDocs)
            } catch (e: Exception) {
                executeRecoveryRollback(recoveryJournalDir, journalFile)
                throw e
            } finally {
                Arrays.fill(dek, 0.toByte())
            }
        }
    }

    suspend fun changeMasterPassword(
        currentPasswordChars: CharArray,
        newPasswordChars: CharArray
    ): Boolean = withContext(Dispatchers.IO) {
        val session = sessionManager.getActiveSession()
            ?: throw IllegalStateException("Vault is locked.")
        require(session.slotId == VaultSessionManager.REAL_SLOT) { "Cannot change master password from decoy session." }
        require(newPasswordChars.isNotEmpty()) { "New password cannot be empty." }

        val envelope = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)
            ?: throw IllegalStateException("Envelope not found.")

        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = envelope.kdfMemoryKb,
            iterations = envelope.kdfIterations,
            parallelism = envelope.kdfParallelism
        )
        val currentKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = currentPasswordChars,
            salt = envelope.kdfSalt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        val unwrappedPayload = CryptoEngine.unwrapEnvelopePayloadOrNull(
            kekBytes = currentKek,
            nonce = envelope.envelopeNonce,
            slotId = VaultSessionManager.REAL_SLOT,
            envelopeVersion = envelope.envelopeVersion,
            params = kdfParams,
            wrappedCiphertext = envelope.wrappedPayloadCiphertext
        )
        Arrays.fill(currentKek, 0.toByte())
        if (unwrappedPayload == null) {
            return@withContext false
        }

        try {
            val newSalt = CryptoEngine.generateSalt()
            val newNonce = CryptoEngine.generateNonce()
            val newKdfParams = CryptoEngine.KdfParams()
            val newKek = CryptoEngine.deriveKeyArgon2id(
                passwordChars = newPasswordChars,
                salt = newSalt,
                params = newKdfParams,
                normalizeUnicodeNfc = true
            )
            val updatedPayload = unwrappedPayload.copy(
                rotatedAtEpochMs = System.currentTimeMillis()
            )
            val newWrappedCiphertext = CryptoEngine.wrapEnvelopePayload(
                kekBytes = newKek,
                nonce = newNonce,
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                params = newKdfParams,
                payload = updatedPayload
            )
            Arrays.fill(newKek, 0.toByte())

            val updatedEnvelope = envelope.copy(
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                kdfAlgorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
                kdfMemoryKb = newKdfParams.memoryKb,
                kdfIterations = newKdfParams.iterations,
                kdfParallelism = newKdfParams.parallelism,
                kdfSalt = newSalt,
                envelopeNonce = newNonce,
                wrappedPayloadCiphertext = newWrappedCiphertext,
                biometricNonce = null,
                biometricWrappedDek = null
            )
            dao.upsertEnvelope(updatedEnvelope)
            true
        } finally {
            unwrappedPayload.wipe()
        }
    }
}
