package com.tpeditz.privatevault.v2.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages Android Keystore wrapping of the Real Vault DEK when Decoy Mode is disabled.
 *
 * Security Rules:
 * - Biometric unlock is permitted ONLY for Slot 0 (Real Vault) and ONLY when Decoy Mode is disabled.
 * - Enabling Decoy Mode immediately deletes the Keystore key and wipes any biometric wrapper bytes.
 * - BiometricPrompt authenticates a cryptographic Cipher object (never a boolean UI callback).
 * - Enforces per-operation Class 3 (BIOMETRIC_STRONG) authentication parameters.
 */
object BiometricKeystoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    internal const val KEY_ALIAS_REAL_VAULT_BIO = "com.tpeditz.privatevault.v2.real_bio_kek"
    internal const val KEY_ALIAS_REAL_VAULT_BIO_ALT = "com.tpeditz.privatevault.v2.real_bio_kek_alt"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val allBioAliases = listOf(KEY_ALIAS_REAL_VAULT_BIO, KEY_ALIAS_REAL_VAULT_BIO_ALT)

    data class StagedEnrollmentCipher(
        val cipher: Cipher,
        val targetAlias: String
    )

    data class WrappedBiometricDek(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    data class BiometricAliasResolution(
        val unlockAlias: String?,
        val shouldPromotePendingAlias: Boolean,
        val staleAliasToRetire: String?,
        val shouldClearPending: Boolean
    )

    fun nonceSha256Hex(nonceBytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(nonceBytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Reconciles staged biometric alias metadata against what actually committed into the SQLite
     * envelope (`dbBiometricNonceSha256`). Guarantees interrupted replacements recover deterministically
     * without retiring a working key before both DB and preferences agree.
     */
    internal fun reconcileAliasState(
        activeAlias: String?,
        pendingAlias: String?,
        pendingNonceSha256: String?,
        dbBiometricNonceSha256: String?,
        hasKeyInKeystore: (String) -> Boolean
    ): BiometricAliasResolution {
        if (dbBiometricNonceSha256.isNullOrBlank()) {
            return BiometricAliasResolution(
                unlockAlias = null,
                shouldPromotePendingAlias = false,
                staleAliasToRetire = null,
                shouldClearPending = !pendingAlias.isNullOrBlank() || !pendingNonceSha256.isNullOrBlank()
            )
        }

        val resolvedActive = activeAlias?.takeIf { it in allBioAliases && hasKeyInKeystore(it) }
        val resolvedPending = pendingAlias?.takeIf { it in allBioAliases && hasKeyInKeystore(it) }

        if (resolvedPending != null && !pendingNonceSha256.isNullOrBlank()) {
            return if (pendingNonceSha256 == dbBiometricNonceSha256) {
                // DB commit succeeded before interruption; promote pending alias and retire the old one.
                BiometricAliasResolution(
                    unlockAlias = resolvedPending,
                    shouldPromotePendingAlias = true,
                    staleAliasToRetire = allBioAliases.firstOrNull { it != resolvedPending && hasKeyInKeystore(it) },
                    shouldClearPending = true
                )
            } else {
                // Interruption occurred before DB commit; preserve active working alias and discard uncommitted stage.
                val fallback = resolvedActive ?: allBioAliases.firstOrNull { it != resolvedPending && hasKeyInKeystore(it) }
                BiometricAliasResolution(
                    unlockAlias = fallback,
                    shouldPromotePendingAlias = false,
                    staleAliasToRetire = resolvedPending.takeIf { it != fallback },
                    shouldClearPending = true
                )
            }
        }

        val fallback = resolvedActive ?: allBioAliases.firstOrNull { hasKeyInKeystore(it) }
        return BiometricAliasResolution(
            unlockAlias = fallback,
            shouldPromotePendingAlias = false,
            staleAliasToRetire = null,
            shouldClearPending = !pendingAlias.isNullOrBlank() || !pendingNonceSha256.isNullOrBlank()
        )
    }

    fun hasKeystoreKey(preferredAlias: String? = null): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!preferredAlias.isNullOrBlank() && keyStore.containsAlias(preferredAlias)) {
                return true
            }
            allBioAliases.any { keyStore.containsAlias(it) }
        } catch (_: Exception) {
            false
        }
    }

    fun isHardwareBackedKey(preferredAlias: String? = null): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val alias = resolveExistingAlias(keyStore, preferredAlias) ?: return false
            val secretKey = keyStore.getKey(alias, null) as? SecretKey
                ?: return false
            val factory = SecretKeyFactory.getInstance(secretKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as? KeyInfo
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val level = keyInfo.securityLevel
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    level == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveExistingAlias(keyStore: KeyStore, preferredAlias: String?): String? {
        if (!preferredAlias.isNullOrBlank() && keyStore.containsAlias(preferredAlias)) {
            return preferredAlias
        }
        return allBioAliases.firstOrNull { keyStore.containsAlias(it) }
    }

    fun selectStagingAlias(currentActiveAlias: String?): String {
        val active = currentActiveAlias?.takeIf { it in allBioAliases } ?: runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            allBioAliases.firstOrNull { ks.containsAlias(it) }
        }.getOrNull()
        return if (active == KEY_ALIAS_REAL_VAULT_BIO) KEY_ALIAS_REAL_VAULT_BIO_ALT else KEY_ALIAS_REAL_VAULT_BIO
    }

    /**
     * Prepares a replacement keystore cipher under an alternate alias so cancelling enrollment
     * never destroys a previously working biometric configuration.
     */
    fun getEncryptCipherForStagedEnrollment(currentActiveAlias: String? = null): StagedEnrollmentCipher? {
        return try {
            val targetAlias = selectStagingAlias(currentActiveAlias)
            deleteAlias(targetAlias)
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                targetAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            keyGenerator.init(builder.build())
            val secretKey = keyGenerator.generateKey()

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            StagedEnrollmentCipher(cipher = cipher, targetAlias = targetAlias)
        } catch (_: Exception) {
            null
        }
    }

    fun getEncryptCipherForEnrollment(): Cipher? {
        return getEncryptCipherForStagedEnrollment(null)?.cipher
    }

    fun commitStagedAliasAndRetirePrevious(committedAlias: String) {
        allBioAliases.filter { it != committedAlias }.forEach { deleteAlias(it) }
    }

    fun discardStagedAlias(stagedAlias: String?) {
        if (!stagedAlias.isNullOrBlank()) {
            deleteAlias(stagedAlias)
        }
    }

    fun retireAlias(alias: String?) {
        if (!alias.isNullOrBlank()) {
            deleteAlias(alias)
        }
    }

    fun getDecryptCipherForUnlock(iv: ByteArray, preferredAlias: String? = null): Cipher? {
        var resolvedAlias: String? = null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val alias = resolveExistingAlias(keyStore, preferredAlias) ?: return null
            resolvedAlias = alias
            val secretKey = keyStore.getKey(alias, null) as? SecretKey
                ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher
        } catch (_: KeyPermanentlyInvalidatedException) {
            resolvedAlias?.let { deleteAlias(it) }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun wrapDekWithCipher(cipher: Cipher, dek: ByteArray): WrappedBiometricDek {
        val ciphertext = cipher.doFinal(dek)
        return WrappedBiometricDek(iv = cipher.iv, ciphertext = ciphertext)
    }

    fun unwrapDekWithCipher(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        return cipher.doFinal(ciphertext)
    }

    private fun deleteAlias(alias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (_: Exception) {
        }
    }

    fun deleteBiometricKey() {
        allBioAliases.forEach { deleteAlias(it) }
    }

    fun canAuthenticate(context: Context): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }
}
