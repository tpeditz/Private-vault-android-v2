package com.tpeditz.privatevault.v2.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun testGenerateRandomBytes() {
        val bytes16 = CryptoEngine.generateRandomBytes(16)
        val bytes32 = CryptoEngine.generateRandomBytes(32)
        assertEquals(16, bytes16.size)
        assertEquals(32, bytes32.size)
        assertFalse(bytes16.all { b -> b == 0.toByte() })
    }

    @Test
    fun testArgon2idKeyDerivationLength() {
        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val params = CryptoEngine.KdfParams(
            memoryKb = 4096, // Fast bounds for test execution
            iterations = 1,
            parallelism = 1
        )
        val derivedKey = CryptoEngine.deriveKeyArgon2id(
            passwordChars = "TestPassword123!".toCharArray(),
            salt = salt,
            params = params,
            outputKeyBytes = CryptoEngine.DEK_BYTES
        )
        assertNotNull(derivedKey)
        assertEquals(32, derivedKey.size)
        assertFalse(derivedKey.all { b -> b == 0.toByte() })
    }

    @Test
    fun testArgon2idDeterminism() {
        val salt = "FixedSalt1234567".toByteArray(StandardCharsets.UTF_8)
        val params = CryptoEngine.KdfParams(
            memoryKb = 4096,
            iterations = 1,
            parallelism = 1
        )
        val key1 = CryptoEngine.deriveKeyArgon2id("MasterSecret".toCharArray(), salt, params, 32)
        val key2 = CryptoEngine.deriveKeyArgon2id("MasterSecret".toCharArray(), salt, params, 32)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun testUnicodeNfcFallbackDerivation() {
        val decomposed = "caf\u0065\u0301" // e + combining acute accent
        val precomposed = "caf\u00e9"     // single character é

        assertEquals(Normalizer.normalize(decomposed, Normalizer.Form.NFC), precomposed)

        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val params = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)

        val nfcKey = CryptoEngine.deriveKeyArgon2id(
            Normalizer.normalize(decomposed, Normalizer.Form.NFC).toCharArray(),
            salt,
            params,
            32
        )
        val directNfcKey = CryptoEngine.deriveKeyArgon2id(
            precomposed.toCharArray(),
            salt,
            params,
            32
        )
        assertArrayEquals(nfcKey, directNfcKey)
    }

    @Test
    fun testWrappedEnvelopeRoundtripSlot0() {
        val kek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)

        val wrapped = CryptoEngine.wrapEnvelope(
            dek = dek,
            kek = kek,
            slotId = 0,
            slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
            decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED
        )

        assertEquals(CryptoEngine.WRAPPED_ENVELOPE_CIPHERTEXT_BYTES, wrapped.size)
        assertEquals(72, wrapped.size)

        val unwrappedDek = CryptoEngine.unwrapEnvelope(
            wrappedEnvelopeCiphertext = wrapped,
            kek = kek,
            slotId = 0
        )
        assertArrayEquals(dek, unwrappedDek)
    }

    @Test
    fun testWrappedEnvelopeRoundtripSlot1Decoy() {
        val kek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val decoyDek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)

        val wrapped = CryptoEngine.wrapEnvelope(
            dek = decoyDek,
            kek = kek,
            slotId = 1,
            slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
            decoyPolicyFlag = CryptoEngine.DECOY_POLICY_ENABLED
        )

        assertEquals(72, wrapped.size)

        val unwrappedDecoyDek = CryptoEngine.unwrapEnvelope(
            wrappedEnvelopeCiphertext = wrapped,
            kek = kek,
            slotId = 1
        )
        assertArrayEquals(decoyDek, unwrappedDecoyDek)
    }

    @Test
    fun testWrappedEnvelopeWrongSlotIdRejected() {
        val kek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)

        val wrapped = CryptoEngine.wrapEnvelope(
            dek = dek,
            kek = kek,
            slotId = 0,
            slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
            decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED
        )

        // Attempting to unwrap Slot 0 wrapped payload with Slot 1 AAD must fail authentication
        try {
            CryptoEngine.unwrapEnvelope(wrapped, kek, slotId = 1)
            fail("Expected AEADBadTagException for slot mismatch")
        } catch (_: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun testWrappedEnvelopeTamperDetection() {
        val kek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)

        val wrapped = CryptoEngine.wrapEnvelope(
            dek = dek,
            kek = kek,
            slotId = 0,
            slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
            decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED
        )

        // Tamper with one byte of the wrapped envelope ciphertext
        val tampered = wrapped.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x5A).toByte()

        try {
            CryptoEngine.unwrapEnvelope(tampered, kek, slotId = 0)
            fail("Expected AEADBadTagException for tampered envelope")
        } catch (_: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun testEncryptDecryptPayloadWithAad() {
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val plaintext = "TopSecretVaultRecordContent".toByteArray(StandardCharsets.UTF_8)
        val aad = CryptoEngine.buildRecordAad(
            recordId = "rec-1234",
            recordType = "NOTE",
            schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
            slotId = 0
        )

        val (ciphertext, nonce) = CryptoEngine.encryptPayload(plaintext, dek, aad)
        assertEquals(CryptoEngine.NONCE_BYTES, nonce.size)
        assertTrue(ciphertext.size >= plaintext.size + CryptoEngine.GCM_TAG_BYTES)

        val decrypted = CryptoEngine.decryptPayload(ciphertext, nonce, dek, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testPayloadTamperedCiphertextThrows() {
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val plaintext = "SecretPayload".toByteArray(StandardCharsets.UTF_8)
        val aad = "FixedAad".toByteArray(StandardCharsets.UTF_8)

        val (ciphertext, nonce) = CryptoEngine.encryptPayload(plaintext, dek, aad)
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        try {
            CryptoEngine.decryptPayload(tampered, nonce, dek, aad)
            fail("Expected AEADBadTagException for tampered ciphertext")
        } catch (_: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun testPayloadMismatchedAadThrows() {
        val dek = CryptoEngine.generateRandomBytes(CryptoEngine.DEK_BYTES)
        val plaintext = "SecretPayload".toByteArray(StandardCharsets.UTF_8)
        val aad1 = "CorrectAad".toByteArray(StandardCharsets.UTF_8)
        val aad2 = "WrongAad".toByteArray(StandardCharsets.UTF_8)

        val (ciphertext, nonce) = CryptoEngine.encryptPayload(plaintext, dek, aad1)

        try {
            CryptoEngine.decryptPayload(ciphertext, nonce, dek, aad2)
            fail("Expected AEADBadTagException for mismatched AAD")
        } catch (_: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun testMasterPasswordChangeAndReWrapping() {
        val initialPassword = "OldPassword2026!".toCharArray()
        val newPassword = "NewPassword2027#UltraSecure".toCharArray()

        val salt1 = CryptoEngine.generateSalt()
        val nonce1 = CryptoEngine.generateNonce()
        val kdfParams1 = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val dek = CryptoEngine.generateDek()

        // 1. Initial wrap with old password
        val kek1 = CryptoEngine.deriveKeyArgon2id(initialPassword, salt1, kdfParams1)
        val initialPayload = CryptoEngine.UnwrappedEnvelopePayload(
            slotRoleFlag = CryptoEngine.SLOT_ROLE_ACTIVE,
            decoyPolicyFlag = CryptoEngine.DECOY_POLICY_DISABLED,
            createdAtEpochMs = 1700000000000L,
            rotatedAtEpochMs = 1700000000000L,
            dekBytes = dek
        )
        val wrappedEnvelope1 = CryptoEngine.wrapEnvelopePayload(
            kekBytes = kek1,
            nonce = nonce1,
            slotId = 0,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = kdfParams1,
            payload = initialPayload
        )

        // Verify initial unwrapping
        val unwrap1 = CryptoEngine.unwrapEnvelopePayloadOrNull(
            kekBytes = kek1,
            nonce = nonce1,
            slotId = 0,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = kdfParams1,
            wrappedCiphertext = wrappedEnvelope1
        )
        assertNotNull(unwrap1)
        assertArrayEquals(dek, unwrap1?.dekBytes)

        // 2. Perform Master Password Change: derive new KEK and re-wrap
        val salt2 = CryptoEngine.generateSalt()
        val nonce2 = CryptoEngine.generateNonce()
        val kdfParams2 = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val kek2 = CryptoEngine.deriveKeyArgon2id(newPassword, salt2, kdfParams2)

        val updatedPayload = unwrap1!!.copy(
            rotatedAtEpochMs = 1710000000000L
        )
        val wrappedEnvelope2 = CryptoEngine.wrapEnvelopePayload(
            kekBytes = kek2,
            nonce = nonce2,
            slotId = 0,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = kdfParams2,
            payload = updatedPayload
        )

        // 3. Verify old password cannot unwrap the new envelope
        val oldKekAttempt = CryptoEngine.unwrapEnvelopePayloadOrNull(
            kekBytes = kek1,
            nonce = nonce2,
            slotId = 0,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = kdfParams2,
            wrappedCiphertext = wrappedEnvelope2
        )
        org.junit.Assert.assertNull("Old KEK must not unwrap the updated envelope", oldKekAttempt)

        // 4. Verify new password successfully unwraps the new envelope and recovers original DEK
        val unwrap2 = CryptoEngine.unwrapEnvelopePayloadOrNull(
            kekBytes = kek2,
            nonce = nonce2,
            slotId = 0,
            envelopeVersion = CryptoEngine.ENVELOPE_VERSION,
            params = kdfParams2,
            wrappedCiphertext = wrappedEnvelope2
        )
        assertNotNull("New KEK must unwrap the envelope", unwrap2)
        assertArrayEquals("DEK must remain unchanged across master password rotation", dek, unwrap2?.dekBytes)
        assertEquals(1710000000000L, unwrap2?.rotatedAtEpochMs)
    }
}
