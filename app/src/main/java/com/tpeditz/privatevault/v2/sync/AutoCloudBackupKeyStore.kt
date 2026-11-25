package com.tpeditz.privatevault.v2.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore wrapper for the user's intentionally chosen automatic
 * cloud backup recovery password.
 *
 * Security boundaries:
 * - Never stores plaintext passwords or keys in DataStore, logs, or WorkManager input data.
 * - Uses AES-256-GCM inside AndroidKeyStore to protect the user-supplied recovery password on-device.
 * - The resulting `.pvault` snapshot remains 100% recoverable on a new device with that same password.
 */
object AutoCloudBackupKeyStore {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "pv2_auto_cloud_recovery_key_v1"
    private const val WRAPPED_FILE_NAME = "auto_cloud_recovery_pwd.enc"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun wrappedFile(context: Context): File = File(context.filesDir, WRAPPED_FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun hasRecoveryPassword(context: Context): Boolean {
        val file = wrappedFile(context)
        return file.exists() && file.length() > GCM_IV_BYTES + 16
    }

    fun storeRecoveryPassword(context: Context, passwordChars: CharArray): Boolean {
        if (passwordChars.isEmpty()) return false
        val charBuf = CharBuffer.wrap(passwordChars)
        val byteBuf = StandardCharsets.UTF_8.encode(charBuf)
        val plainBytes = ByteArray(byteBuf.remaining())
        byteBuf.get(plainBytes)

        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plainBytes)

            val target = wrappedFile(context)
            val temp = File(context.filesDir, "$WRAPPED_FILE_NAME.tmp")
            FileOutputStream(temp).use { fos ->
                fos.write(ByteBuffer.allocate(4).putInt(iv.size).array())
                fos.write(iv)
                fos.write(ciphertext)
                fos.flush()
                fos.fd.sync()
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            SafeVaultLogger.e("CloudKeyStore", "Failed to store wrapped recovery password", e)
            false
        } finally {
            Arrays.fill(plainBytes, 0.toByte())
            if (byteBuf.hasArray()) {
                Arrays.fill(byteBuf.array(), 0.toByte())
            }
        }
    }

    fun readRecoveryPasswordOrNull(context: Context): CharArray? {
        val file = wrappedFile(context)
        if (!file.exists() || file.length() <= 4 + GCM_IV_BYTES + 16) return null

        var plainBytes = ByteArray(0)
        return try {
            val all = file.readBytes()
            val bb = ByteBuffer.wrap(all)
            val ivLen = bb.int
            if (ivLen !in 12..16 || all.size <= 4 + ivLen + 16) return null
            val iv = ByteArray(ivLen)
            bb.get(iv)
            val ciphertext = ByteArray(bb.remaining())
            bb.get(ciphertext)

            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val secretKey = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            plainBytes = cipher.doFinal(ciphertext)

            val charBuf = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
            val result = CharArray(charBuf.remaining())
            charBuf.get(result)
            result
        } catch (e: Exception) {
            SafeVaultLogger.w("CloudKeyStore", "Could not unwrap recovery password: ${e.message}")
            null
        } finally {
            if (plainBytes.isNotEmpty()) {
                Arrays.fill(plainBytes, 0.toByte())
            }
        }
    }

    fun clearRecoveryPassword(context: Context) {
        runCatching { wrappedFile(context).delete() }
        runCatching { File(context.filesDir, "$WRAPPED_FILE_NAME.tmp").delete() }
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        }
    }
}
