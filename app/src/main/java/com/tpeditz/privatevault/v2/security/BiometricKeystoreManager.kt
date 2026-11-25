package com.tpeditz.privatevault.v2.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyStore
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
    private const val KEY_ALIAS_REAL_VAULT_BIO = "com.tpeditz.privatevault.v2.real_bio_kek"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    data class WrappedBiometricDek(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    fun hasKeystoreKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(KEY_ALIAS_REAL_VAULT_BIO)
        } catch (_: Exception) {
            false
        }
    }

    fun isHardwareBackedKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS_REAL_VAULT_BIO, null) as? SecretKey
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

    fun getEncryptCipherForEnrollment(): Cipher? {
        return try {
            deleteBiometricKey()
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS_REAL_VAULT_BIO,
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
            cipher
        } catch (_: Exception) {
            null
        }
    }

    fun getDecryptCipherForUnlock(iv: ByteArray): Cipher? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS_REAL_VAULT_BIO, null) as? SecretKey
                ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteBiometricKey()
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

    fun deleteBiometricKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS_REAL_VAULT_BIO)) {
                keyStore.deleteEntry(KEY_ALIAS_REAL_VAULT_BIO)
            }
        } catch (_: Exception) {
        }
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
