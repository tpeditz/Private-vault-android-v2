package com.tpeditz.privatevault.v2.security

import com.tpeditz.privatevault.v2.data.AuthResult
import com.tpeditz.privatevault.v2.data.EncryptedBackupV2Engine
import com.tpeditz.privatevault.v2.data.InMemoryVaultDao
import com.tpeditz.privatevault.v2.data.NoteRecord
import com.tpeditz.privatevault.v2.data.VaultEnvelopeEntity
import com.tpeditz.privatevault.v2.data.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PasswordPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEnforcesTwelvePlusCharsWithSpacesAndUnicodeOnNewPasswords() {
        assertFalse(PasswordPolicy.validateNewPassword("".toCharArray()).isValid)
        assertFalse(PasswordPolicy.validateNewPassword("shortPass!".toCharArray()).isValid)
        assertFalse(PasswordPolicy.validateNewPassword("12345678901".toCharArray()).isValid)

        val validSpacePassphrase = PasswordPolicy.validateNewPassword("correct horse".toCharArray())
        assertTrue(validSpacePassphrase.isValid)
        assertNotNull(validSpacePassphrase.strengthReport)

        val validUnicodePassphrase = PasswordPolicy.validateNewPassword("kāfī surakshit".toCharArray())
        assertTrue(validUnicodePassphrase.isValid)

        assertTrue(PasswordPolicy.validateNewPassword("match12chars+", "match12chars+").isValid)
        assertFalse(PasswordPolicy.validateNewPassword("match12chars+", "different12+").isValid)
    }

    @Test
    fun testLegacyShortPasswordDetectionAndRepositoryBackwardCompatibility() = runBlocking {
        assertTrue(PasswordPolicy.isShortLegacyPassword("legacy8!".toCharArray()))
        assertFalse(PasswordPolicy.isShortLegacyPassword("enough12chars".toCharArray()))
        assertFalse(PasswordPolicy.isShortLegacyPassword(null))

        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repo = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        // New vault creation rejects < 12 character passwords
        val weakCreate = repo.createInitialVault("short8!".toCharArray())
        assertTrue("Expected weak new password rejection", weakCreate is AuthResult.Error)
        assertFalse(repo.isVaultInitialized())

        // Simulate a pre-existing legacy V2.0.0 vault provisioned with an 8-character password
        val legacyPassword = "TEST_OLD".toCharArray()
        val salt = CryptoEngine.generateSalt()
        val nonce = CryptoEngine.generateNonce()
        val params = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val kek = CryptoEngine.deriveKeyArgon2id(legacyPassword, salt, params)
        val dek = CryptoEngine.generateDek()
        val wrappedReal = CryptoEngine.wrapEnvelopePayload(
            kekBytes = kek,
            nonce = nonce,
            slotId = VaultSessionManager.REAL_SLOT,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = params,
            payload = CryptoEngine.UnwrappedEnvelopePayload(
                slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
                decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
                createdAtEpochMs = 1_000L,
                rotatedAtEpochMs = 1_000L,
                dekBytes = dek
            )
        )
        dao.upsertEnvelope(
            VaultEnvelopeEntity(
                slotId = VaultSessionManager.REAL_SLOT,
                envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
                kdfAlgorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
                kdfSalt = salt,
                kdfMemoryKb = params.memoryKb,
                kdfIterations = params.iterations,
                kdfParallelism = params.parallelism,
                envelopeNonce = nonce,
                wrappedPayloadCiphertext = wrappedReal
            )
        )

        // Existing legacy vault MUST still unlock seamlessly with < 12 character password
        val legacyUnlock = repo.unlockVault(legacyPassword)
        assertTrue("Existing legacy vault must still unlock", legacyUnlock is AuthResult.Success)
        assertTrue(sessionManager.isUnlocked())

        // Legacy backups encrypted with < 12 character password MUST still decrypt & inspect cleanly
        val baos = ByteArrayOutputStream()
        EncryptedBackupV2Engine.createStreamingBackup(
            items = listOf(NoteRecord(id = "n1", title = "Legacy Note", body = "Accessible")),
            backupPasswordChars = "TEST_OLD".toCharArray(),
            outputStream = baos,
            appFilesDir = filesDir,
            kdfParams = params
        )
        val staging = tempFolder.newFolder("legacy_stage")
        val staged = EncryptedBackupV2Engine.inspectAndStageBackup(
            ByteArrayInputStream(baos.toByteArray()),
            "TEST_OLD".toCharArray(),
            staging
        )
        assertEquals(1, staged.payload.items.size)
    }
}
