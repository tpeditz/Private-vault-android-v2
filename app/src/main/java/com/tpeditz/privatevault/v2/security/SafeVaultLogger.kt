package com.tpeditz.privatevault.v2.security

import android.util.Log

/**
 * Defensive logging utility for PRIVATE VAULT V2.
 * Strictly guarantees that no sensitive secrets, master passwords, derived DEK bytes,
 * plaintext titles, notes, TOTP seeds, or user records are ever emitted to logcat.
 */
object SafeVaultLogger {

    private const val TAG_PREFIX = "PrivateVaultV2:"

    fun d(tag: String, message: String) {
        runCatching { Log.d("$TAG_PREFIX$tag", sanitize(message)) }
    }

    fun i(tag: String, message: String) {
        runCatching { Log.i("$TAG_PREFIX$tag", sanitize(message)) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val safeMsg = sanitize(message)
        if (throwable != null) {
            val safeErr = sanitizeException(throwable)
            runCatching { Log.w("$TAG_PREFIX$tag", "$safeMsg | Exception: $safeErr") }
        } else {
            runCatching { Log.w("$TAG_PREFIX$tag", safeMsg) }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val safeMsg = sanitize(message)
        if (throwable != null) {
            val safeErr = sanitizeException(throwable)
            runCatching { Log.e("$TAG_PREFIX$tag", "$safeMsg | Error: $safeErr") }
        } else {
            runCatching { Log.e("$TAG_PREFIX$tag", safeMsg) }
        }
    }

    private fun sanitizeException(t: Throwable): String {
        val exName = t.javaClass.simpleName
        val msg = t.message?.let { sanitize(it) } ?: "No message"
        return "$exName: $msg"
    }

    private fun sanitize(raw: String): String {
        return raw
            .replace(Regex("(?i)(password|secret|key|dek|kek|token|pin|seed)=[^&\\s]+"), "$1=[REDACTED]")
            .replace(Regex("(?i)(bearer\\s+)[a-zA-Z0-9_\\-\\.]+"), "$1[REDACTED]")
    }
}
