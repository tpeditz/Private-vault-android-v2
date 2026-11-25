package com.tpeditz.privatevault.v2.security

import android.os.Build
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Core cryptographic engine for PRIVATE VAULT V2.
 *
 * Security Architecture:
 * - Password Key Derivation: Argon2id v1.3 (RFC 9106) via BouncyCastle
 * - Authenticated Encryption: AES-256-GCM with 96-bit CSPRNG nonces and 128-bit authentication tags
 * - Key Envelopes: Unified 72-byte ciphertext (56-byte inner payload + 16-byte GCM tag) for both
 *   Slot 0 (Real Vault) and Slot 1 (Decoy Vault) with zero plaintext flags in SQLite.
 * - Unicode NFC normalization with automatic fallback to legacy non-NFC byte derivation.
 */
object CryptoEngine {

    const val ENVELOPE_VERSION = 1
    const val RECORD_SCHEMA_VERSION = 1
    const val BACKUP_SCHEMA_VERSION = 2
    const val KDF_ALGORITHM_ARGON2ID = "ARGON2ID_V13"

    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val DEK_BYTES = 32
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_BYTES = 16

    // Inner envelope binary layout (56 bytes):
    // [0..3]   4B Magic "PVE1"
    // [4]      1B slotRoleFlag (0x01 = active configured vault, 0x00 = unconfigured dummy slot)
    // [5]      1B decoyPolicyFlag (0x01 = decoy policy active, 0x00 = decoy disabled)
    // [6..7]   2B reservedFlags (0x0000)
    // [8..15]  8B createdAtEpochMs
    // [16..23] 8B rotatedAtEpochMs
    // [24..55] 32B dekBytes
    const val INNER_ENVELOPE_BYTES = 56
    const val WRAPPED_ENVELOPE_CIPHERTEXT_BYTES = INNER_ENVELOPE_BYTES + GCM_TAG_BYTES // 72 bytes

    const val SLOT_ROLE_DUMMY: Byte = 0x00
    const val SLOT_ROLE_ACTIVE: Byte = 0x01

    const val DECOY_POLICY_DISABLED: Byte = 0x00
    const val DECOY_POLICY_ENABLED: Byte = 0x01

    const val PROPOSED_STANDARD_MEMORY_KB = 32768 // 32 MiB per slot
    const val PROPOSED_STANDARD_ITERATIONS = 3
    const val PROPOSED_STANDARD_PARALLELISM = 2

    const val PROPOSED_CONSTRAINED_MEMORY_KB = 16384 // 16 MiB per slot for lower-RAM devices
    const val PROPOSED_CONSTRAINED_ITERATIONS = 4
    const val PROPOSED_CONSTRAINED_PARALLELISM = 2

    const val MIN_ALLOWED_MEMORY_KB = 4096      // 4 MiB minimum
    const val MAX_ALLOWED_MEMORY_KB = 131072    // 128 MiB cap
    const val MIN_ALLOWED_ITERATIONS = 1
    const val MAX_ALLOWED_ITERATIONS = 12
    const val MIN_ALLOWED_PARALLELISM = 1
    const val MAX_ALLOWED_PARALLELISM = 4

    private val secureRandom = SecureRandom()

    data class KdfParams(
        val algorithm: String = KDF_ALGORITHM_ARGON2ID,
        val memoryKb: Int = selectDefaultMemoryKb(),
        val iterations: Int = selectDefaultIterations(),
        val parallelism: Int = PROPOSED_STANDARD_PARALLELISM,
        val isMeasuredOnDevice: Boolean = false
    ) {
        fun validateBounds() {
            require(algorithm == KDF_ALGORITHM_ARGON2ID) {
                "Unsupported KDF algorithm: $algorithm"
            }
            require(memoryKb in MIN_ALLOWED_MEMORY_KB..MAX_ALLOWED_MEMORY_KB) {
                "KDF memory ($memoryKb KB) out of safe bounds [$MIN_ALLOWED_MEMORY_KB..$MAX_ALLOWED_MEMORY_KB]"
            }
            require(iterations in MIN_ALLOWED_ITERATIONS..MAX_ALLOWED_ITERATIONS) {
                "KDF iterations ($iterations) out of safe bounds [$MIN_ALLOWED_ITERATIONS..$MAX_ALLOWED_ITERATIONS]"
            }
            require(parallelism in MIN_ALLOWED_PARALLELISM..MAX_ALLOWED_PARALLELISM) {
                "KDF parallelism ($parallelism) out of safe bounds [$MIN_ALLOWED_PARALLELISM..$MAX_ALLOWED_PARALLELISM]"
            }
        }
    }

    data class KdfBenchmarkResult(
        val deviceModel: String,
        val androidSdkInt: Int,
        val memoryKbPerSlot: Int,
        val iterations: Int,
        val parallelism: Int,
        val singleSlotDurationMs: Long,
        val twoSlotUnlockDurationMs: Long,
        val maxHeapMb: Long,
        val usedHeapMbAfterRun: Long
    )

    data class UnwrappedEnvelopePayload(
        val slotRoleFlag: Byte,
        val decoyPolicyFlag: Byte = DECOY_POLICY_DISABLED,
        val createdAtEpochMs: Long,
        val rotatedAtEpochMs: Long,
        val dekBytes: ByteArray
    ) {
        val isActiveVault: Boolean
            get() = slotRoleFlag == SLOT_ROLE_ACTIVE

        val isDecoyPolicyEnabled: Boolean
            get() = decoyPolicyFlag == DECOY_POLICY_ENABLED

        fun wipe() {
            Arrays.fill(dekBytes, 0.toByte())
        }
    }

    fun selectDefaultMemoryKb(): Int {
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return if (maxHeapMb < 192) {
            PROPOSED_CONSTRAINED_MEMORY_KB
        } else {
            PROPOSED_STANDARD_MEMORY_KB
        }
    }

    fun selectDefaultIterations(): Int {
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return if (maxHeapMb < 192) {
            PROPOSED_CONSTRAINED_ITERATIONS
        } else {
            PROPOSED_STANDARD_ITERATIONS
        }
    }

    fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun generateRandomBytes(length: Int): ByteArray = randomBytes(length)

    fun generateSalt(): ByteArray = randomBytes(SALT_BYTES)
    fun generateNonce(): ByteArray = randomBytes(NONCE_BYTES)
    fun generateDek(): ByteArray = randomBytes(DEK_BYTES)

    /**
     * Derives a 256-bit key using Argon2id v1.3 via BouncyCastle.
     * Memory-hard derivation calibrated for mobile devices with automatic Unicode NFC canonicalization.
     */
    fun deriveKeyArgon2id(
        passwordChars: CharArray,
        salt: ByteArray,
        params: KdfParams,
        normalizeUnicodeNfc: Boolean = true
    ): ByteArray = deriveKeyArgon2id(passwordChars, salt, params, DEK_BYTES, normalizeUnicodeNfc)

    fun deriveKeyArgon2id(
        passwordChars: CharArray,
        salt: ByteArray,
        params: KdfParams,
        outputKeyBytes: Int,
        normalizeUnicodeNfc: Boolean = true
    ): ByteArray {
        params.validateBounds()
        require(salt.size == SALT_BYTES) { "Invalid salt length: ${salt.size}" }

        val passwordBytes = charArrayToUtf8Bytes(passwordChars, normalizeUnicodeNfc)
        val output = ByteArray(outputKeyBytes)
        var succeeded = false
        try {
            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKb)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)

            val generator = Argon2BytesGenerator()
            generator.init(builder.build())
            generator.generateBytes(passwordBytes, output, 0, output.size)
            succeeded = true
            return output
        } finally {
            Arrays.fill(passwordBytes, 0.toByte())
            if (!succeeded) {
                Arrays.fill(output, 0.toByte())
            }
        }
    }

    /**
     * Benchmarks device Argon2id performance in Settings > Advanced.
     */
    fun measureKdfBenchmark(params: KdfParams = KdfParams()): KdfBenchmarkResult {
        val samplePassword = "BenchmarkSamplePassphrase!2026".toCharArray()
        val salt0 = generateSalt()
        val salt1 = generateSalt()
        var k0: ByteArray? = null
        var k1: ByteArray? = null
        try {
            val startSingle = System.nanoTime()
            k0 = deriveKeyArgon2id(samplePassword, salt0, params)
            val singleMs = ((System.nanoTime() - startSingle) / 1_000_000L).coerceAtLeast(1L)

            val startSecond = System.nanoTime()
            k1 = deriveKeyArgon2id(samplePassword, salt1, params)
            val secondMs = ((System.nanoTime() - startSecond) / 1_000_000L).coerceAtLeast(1L)

            val runtime = Runtime.getRuntime()
            val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
            val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val modelName = try {
                "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            } catch (_: Throwable) {
                "Android Device"
            }

            return KdfBenchmarkResult(
                deviceModel = modelName.ifBlank { "Android Device" },
                androidSdkInt = Build.VERSION.SDK_INT,
                memoryKbPerSlot = params.memoryKb,
                iterations = params.iterations,
                parallelism = params.parallelism,
                singleSlotDurationMs = singleMs,
                twoSlotUnlockDurationMs = singleMs + secondMs,
                maxHeapMb = maxHeapMb,
                usedHeapMbAfterRun = usedHeapMb
            )
        } finally {
            k0?.let { Arrays.fill(it, 0.toByte()) }
            k1?.let { Arrays.fill(it, 0.toByte()) }
            Arrays.fill(samplePassword, '\u0000')
            Arrays.fill(salt0, 0.toByte())
            Arrays.fill(salt1, 0.toByte())
        }
    }

    fun buildEnvelopeAad(
        slotId: Int,
        envelopeVersion: Int,
        params: KdfParams
    ): ByteArray {
        val aadString = "PV1-ENV|slot=$slotId|ver=$envelopeVersion|alg=${params.algorithm}|m=${params.memoryKb}|t=${params.iterations}|p=${params.parallelism}"
        return aadString.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildRecordAad(
        vaultSlot: Int,
        recordId: String,
        schemaVersion: Int = RECORD_SCHEMA_VERSION
    ): ByteArray {
        val aadString = "PV1-REC|slot=$vaultSlot|id=$recordId|ver=$schemaVersion"
        return aadString.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildRecordAad(
        recordId: String,
        recordType: String = "",
        schemaVersion: Int = RECORD_SCHEMA_VERSION,
        slotId: Int = 0
    ): ByteArray = buildRecordAad(slotId, recordId, schemaVersion)

    fun buildBackupAad(
        schemaVersion: Int,
        params: KdfParams = KdfParams(),
        kdfParams: KdfParams = params
    ): ByteArray {
        val effective = if (params != KdfParams()) params else kdfParams
        val aadString = "PV1-BACKUP|ver=$schemaVersion|alg=${effective.algorithm}|m=${effective.memoryKb}|t=${effective.iterations}|p=${effective.parallelism}"
        return aadString.toByteArray(StandardCharsets.UTF_8)
    }

    fun encryptPayload(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        aad: ByteArray,
        customNonce: ByteArray? = null
    ): Pair<ByteArray, ByteArray> {
        val nonce = customNonce ?: generateNonce()
        val ciphertext = encryptAesGcm(keyBytes, nonce, plaintext, aad)
        return Pair(ciphertext, nonce)
    }

    fun decryptPayload(
        ciphertext: ByteArray,
        nonce: ByteArray,
        keyBytes: ByteArray,
        aad: ByteArray
    ): ByteArray {
        return decryptAesGcm(keyBytes, nonce, ciphertext, aad)
    }

    private fun deriveSlotNonce(slotId: Int): ByteArray {
        val n = ByteArray(NONCE_BYTES)
        n[0] = 'P'.code.toByte()
        n[1] = 'V'.code.toByte()
        n[2] = slotId.toByte()
        return n
    }

    fun wrapEnvelope(
        dek: ByteArray,
        kek: ByteArray,
        slotId: Int,
        slotRoleFlag: Byte = SLOT_ROLE_ACTIVE,
        decoyPolicyFlag: Byte = DECOY_POLICY_DISABLED,
        nonce: ByteArray = deriveSlotNonce(slotId),
        kdfParams: KdfParams = KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
    ): ByteArray {
        val payload = UnwrappedEnvelopePayload(
            slotRoleFlag = slotRoleFlag,
            decoyPolicyFlag = decoyPolicyFlag,
            createdAtEpochMs = System.currentTimeMillis(),
            rotatedAtEpochMs = System.currentTimeMillis(),
            dekBytes = dek
        )
        return wrapEnvelopePayload(kek, nonce, slotId, ENVELOPE_VERSION, kdfParams, payload)
    }

    fun unwrapEnvelope(
        wrappedEnvelopeCiphertext: ByteArray,
        kek: ByteArray,
        slotId: Int,
        nonce: ByteArray = deriveSlotNonce(slotId),
        kdfParams: KdfParams = KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
    ): ByteArray {
        val unwrapped = unwrapEnvelopePayloadOrNull(kek, nonce, slotId, ENVELOPE_VERSION, kdfParams, wrappedEnvelopeCiphertext)
            ?: throw AEADBadTagException("Authentication tag mismatch or invalid envelope")
        return unwrapped.dekBytes
    }

    /**
     * Packs and encrypts the 56-byte inner envelope structure into a 72-byte AES-256-GCM ciphertext.
     */
    fun wrapEnvelopePayload(
        kekBytes: ByteArray,
        nonce: ByteArray,
        slotId: Int,
        envelopeVersion: Int,
        params: KdfParams,
        payload: UnwrappedEnvelopePayload
    ): ByteArray {
        require(payload.dekBytes.size == DEK_BYTES) { "DEK must be 32 bytes" }
        val innerBytes = ByteArray(INNER_ENVELOPE_BYTES)
        try {
            val buffer = ByteBuffer.wrap(innerBytes)
            buffer.put('P'.code.toByte())
            buffer.put('V'.code.toByte())
            buffer.put('E'.code.toByte())
            buffer.put('1'.code.toByte())
            buffer.put(payload.slotRoleFlag)
            buffer.put(payload.decoyPolicyFlag)
            buffer.put(0.toByte())
            buffer.put(0.toByte())
            buffer.putLong(payload.createdAtEpochMs)
            buffer.putLong(payload.rotatedAtEpochMs)
            buffer.put(payload.dekBytes)

            val aad = buildEnvelopeAad(slotId, envelopeVersion, params)
            val ciphertext = encryptAesGcm(
                keyBytes = kekBytes,
                nonce = nonce,
                plaintext = innerBytes,
                aad = aad
            )
            check(ciphertext.size == WRAPPED_ENVELOPE_CIPHERTEXT_BYTES) {
                "Unexpected wrapped envelope length: ${ciphertext.size}"
            }
            return ciphertext
        } finally {
            Arrays.fill(innerBytes, 0.toByte())
        }
    }

    /**
     * Decrypts and validates a 72-byte wrapped envelope ciphertext.
     * Returns null if authentication fails.
     */
    fun unwrapEnvelopePayloadOrNull(
        kekBytes: ByteArray,
        nonce: ByteArray,
        slotId: Int,
        envelopeVersion: Int,
        params: KdfParams,
        wrappedCiphertext: ByteArray
    ): UnwrappedEnvelopePayload? {
        if (wrappedCiphertext.size != WRAPPED_ENVELOPE_CIPHERTEXT_BYTES) return null
        val aad = buildEnvelopeAad(slotId, envelopeVersion, params)
        val decrypted = try {
            decryptAesGcm(
                keyBytes = kekBytes,
                nonce = nonce,
                ciphertext = wrappedCiphertext,
                aad = aad
            )
        } catch (_: Exception) {
            return null
        }

        if (decrypted.size != INNER_ENVELOPE_BYTES) {
            Arrays.fill(decrypted, 0.toByte())
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(decrypted)
            val m0 = buffer.get()
            val m1 = buffer.get()
            val m2 = buffer.get()
            val m3 = buffer.get()
            if (m0 != 'P'.code.toByte() || m1 != 'V'.code.toByte() || m2 != 'E'.code.toByte() || m3 != '1'.code.toByte()) {
                return null
            }
            val roleFlag = buffer.get()
            val decoyPolicyFlag = buffer.get()
            val reserved2 = buffer.get()
            val reserved3 = buffer.get()

            if (roleFlag != SLOT_ROLE_DUMMY && roleFlag != SLOT_ROLE_ACTIVE) {
                return null
            }
            if (decoyPolicyFlag != DECOY_POLICY_DISABLED && decoyPolicyFlag != DECOY_POLICY_ENABLED) {
                return null
            }
            if (reserved2 != 0.toByte() || reserved3 != 0.toByte()) {
                return null
            }

            val createdAt = buffer.long
            val rotatedAt = buffer.long
            if (createdAt < 0L || rotatedAt < 0L) {
                return null
            }
            val dek = ByteArray(DEK_BYTES)
            buffer.get(dek)

            return UnwrappedEnvelopePayload(
                slotRoleFlag = roleFlag,
                decoyPolicyFlag = decoyPolicyFlag,
                createdAtEpochMs = createdAt,
                rotatedAtEpochMs = rotatedAt,
                dekBytes = dek
            )
        } finally {
            Arrays.fill(decrypted, 0.toByte())
        }
    }

    /**
     * Encrypts arbitrary bytes with AES-256-GCM and Associated Authenticated Data (AAD).
     */
    fun encryptAesGcm(
        keyBytes: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(keyBytes.size == DEK_BYTES) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "GCM nonce must be 12 bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts AES-256-GCM ciphertext with Associated Authenticated Data (AAD).
     * Throws AEADBadTagException on any tampering, wrong key, or AAD mismatch.
     */
    @Throws(AEADBadTagException::class)
    fun decryptAesGcm(
        keyBytes: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(keyBytes.size == DEK_BYTES) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "GCM nonce must be 12 bytes" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "Ciphertext shorter than GCM tag" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    fun hasDistinctLegacyNonNfcForm(chars: CharArray): Boolean {
        if (chars.none { it.code > 0x7F }) return false
        return !Normalizer.isNormalized(CharBuffer.wrap(chars), Normalizer.Form.NFC)
    }

    private fun charArrayToUtf8Bytes(
        chars: CharArray,
        normalizeUnicodeNfc: Boolean = true
    ): ByteArray {
        val charBuffer = if (normalizeUnicodeNfc && chars.any { it.code > 0x7F }) {
            val normalized = Normalizer.normalize(CharBuffer.wrap(chars), Normalizer.Form.NFC)
            CharBuffer.wrap(normalized)
        } else {
            CharBuffer.wrap(chars)
        }
        val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) {
            Arrays.fill(byteBuffer.array(), 0.toByte())
        }
        return bytes
    }
}
