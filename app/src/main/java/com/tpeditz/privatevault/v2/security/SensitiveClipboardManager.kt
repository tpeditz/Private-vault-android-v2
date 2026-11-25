package com.tpeditz.privatevault.v2.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays

/**
 * Manages copying sensitive vault secrets to the Android system clipboard with:
 * 1. `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ (API 33+) to mask system clipboard previews.
 * 2. Ownership verification via SHA-256 digest before auto-clearing so unrelated user clipboard
 *    items copied after the secret are never accidentally erased.
 * 3. Deferred foreground clearing when background restrictions prevent reading/clearing the clipboard.
 * 4. Public text copying for developer contacts without sensitive tracking.
 */
class SensitiveClipboardManager(
    context: Context,
    private val scope: CoroutineScope
) {
    constructor(context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val clipboardManager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Volatile
    private var lastCopiedDigest: ByteArray? = null

    @Volatile
    private var pendingClearDigest: ByteArray? = null

    @Volatile
    private var pendingClearExpiresAtMs: Long = 0L

    private var clearJob: Job? = null

    private val _activeCountdownSeconds = MutableStateFlow<Int?>(null)
    val activeCountdownSeconds: StateFlow<Int?> = _activeCountdownSeconds.asStateFlow()

    companion object {
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
        private const val PENDING_CLEAR_RETENTION_MS = 15 * 60 * 1000L // 15 minutes
    }

    @Synchronized
    fun copySensitiveSecret(
        label: String,
        secretValue: String,
        ttlSeconds: Int = 30
    ) {
        if (secretValue.isEmpty()) return

        val clip = ClipData.newPlainText(label, secretValue)
        val extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean(EXTRA_IS_SENSITIVE_COMPAT, true)
            }
        }
        clip.description.extras = extras

        val digest = sha256(secretValue)
        wipeDigest(lastCopiedDigest)
        wipeDigest(pendingClearDigest)
        lastCopiedDigest = digest
        pendingClearDigest = null
        pendingClearExpiresAtMs = 0L

        clipboardManager.setPrimaryClip(clip)

        clearJob?.cancel()
        if (ttlSeconds <= 0) {
            _activeCountdownSeconds.value = null
            return
        }

        clearJob = scope.launch {
            var remaining = ttlSeconds
            while (remaining > 0) {
                _activeCountdownSeconds.value = remaining
                delay(1_000L)
                remaining--
            }
            _activeCountdownSeconds.value = null
            synchronized(this@SensitiveClipboardManager) {
                if (lastCopiedDigest === digest) clearIfStillOwned()
            }
        }
    }

    @Synchronized
    fun copyPublicText(label: String, text: String) {
        if (text.isEmpty()) return
        clearJob?.cancel()
        _activeCountdownSeconds.value = null
        wipeDigest(lastCopiedDigest)
        wipeDigest(pendingClearDigest)
        lastCopiedDigest = null
        pendingClearDigest = null
        pendingClearExpiresAtMs = 0L

        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
    }

    @Synchronized
    fun clearIfStillOwned(): Boolean {
        val expected = lastCopiedDigest ?: return false
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        if (clip == null) {
            // Android may deny clipboard reads in the background. Keep only the digest.
            wipeDigest(pendingClearDigest)
            pendingClearDigest = expected.copyOf()
            pendingClearExpiresAtMs = System.currentTimeMillis() + PENDING_CLEAR_RETENTION_MS
            wipeDigest(lastCopiedDigest)
            lastCopiedDigest = null
            return false
        }
        if (clip.itemCount == 0) {
            wipeDigest(lastCopiedDigest)
            lastCopiedDigest = null
            return false
        }
        val currentText = clip.getItemAt(0)?.text?.toString().orEmpty()
        val currentDigest = sha256(currentText)
        return if (MessageDigest.isEqual(expected, currentDigest)) {
            val cleared = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboardManager.clearPrimaryClip()
                else clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
            }.isSuccess
            if (!cleared) {
                wipeDigest(pendingClearDigest)
                pendingClearDigest = expected.copyOf()
                pendingClearExpiresAtMs = System.currentTimeMillis() + PENDING_CLEAR_RETENTION_MS
            }
            wipeDigest(lastCopiedDigest)
            lastCopiedDigest = null
            cleared
        } else {
            wipeDigest(lastCopiedDigest)
            lastCopiedDigest = null
            false
        }
    }

    @Synchronized
    fun onAppForegrounded() {
        val pending = pendingClearDigest ?: return
        val expires = pendingClearExpiresAtMs
        pendingClearDigest = null
        pendingClearExpiresAtMs = 0L
        if (System.currentTimeMillis() <= expires) {
            wipeDigest(lastCopiedDigest)
            lastCopiedDigest = pending
            clearIfStillOwned()
        } else wipeDigest(pending)
    }

    @Synchronized
    fun onLock() {
        clearIfStillOwned()
        clearJob?.cancel()
        _activeCountdownSeconds.value = null
    }

    private fun sha256(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(StandardCharsets.UTF_8))
    }

    private fun wipeDigest(digest: ByteArray?) {
        if (digest != null) {
            Arrays.fill(digest, 0.toByte())
        }
    }
}
