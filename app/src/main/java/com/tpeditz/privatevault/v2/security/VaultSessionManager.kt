package com.tpeditz.privatevault.v2.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

/**
 * Strongly typed session capability manager for PRIVATE VAULT V2.
 *
 * Isolation Rules:
 * - Slot 0 (REAL_SLOT = 0) and Slot 1 (DECOY_SLOT = 1) never share an unlocked DEK.
 * - Unlocking one slot destroys any previous session and increments the atomic sessionGenerationId.
 * - Every lock event increments `authRequestGeneration`, invalidating any in-flight KDF
 *   unlock or biometric unlock job so a late completion after screen-off/lock cannot reopen a vault.
 * - Decoy sessions contain zero UI flags indicating they are decoy; only Real Vault
 *   security configuration flows verify `session.canManageVaultSecurity`.
 */
class VaultSessionManager(
    private val monotonicClockMs: () -> Long = {
        try {
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed > 0L) elapsed else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) {

    companion object {
        const val REAL_SLOT = 0
        const val DECOY_SLOT = 1
        const val FILE_PICKER_GRANT_WINDOW_MS = 120_000L // 120 seconds SAF immunity window
    }

    sealed interface SessionState {
        data object Locked : SessionState

        class Unlocked(
            val sessionId: Long,
            val slotId: Int,
            private val internalDekBytes: ByteArray,
            val isDecoyConfiguredInRealVault: Boolean = false,
            val unlockedAtEpochMs: Long = System.currentTimeMillis()
        ) : SessionState {

            fun borrowDekCopy(): ByteArray {
                check(!isWiped) { "Session $sessionId has already been locked and wiped." }
                return internalDekBytes.copyOf()
            }

            val canManageVaultSecurity: Boolean
                get() = slotId == REAL_SLOT

            @Volatile
            var isWiped: Boolean = false
                private set

            internal fun wipeKeyMaterial() {
                isWiped = true
                Arrays.fill(internalDekBytes, 0.toByte())
            }

            override fun toString(): String {
                return "VaultSession.Unlocked(sessionId=$sessionId, active=${!isWiped})"
            }
        }
    }

    private val generationCounter = AtomicLong(1000L)
    private val authRequestGenerationCounter = AtomicLong(5000L)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Locked)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    @Volatile
    private var lastBackgroundedAtMonotonicMs: Long? = null

    @Volatile
    private var filePickerGrantUntilMonotonicMs: Long = 0L

    fun currentAuthRequestGeneration(): Long = authRequestGenerationCounter.get()

    @Synchronized
    fun openSession(
        slotId: Int,
        dekBytes: ByteArray,
        isDecoyConfiguredInRealVault: Boolean = false
    ): SessionState.Unlocked {
        lockNow()
        val newId = generationCounter.incrementAndGet()
        val session = SessionState.Unlocked(
            sessionId = newId,
            slotId = slotId,
            internalDekBytes = dekBytes.copyOf(),
            isDecoyConfiguredInRealVault = if (slotId == REAL_SLOT) isDecoyConfiguredInRealVault else false
        )
        lastBackgroundedAtMonotonicMs = null
        filePickerGrantUntilMonotonicMs = 0L
        _sessionState.value = session
        return session
    }

    @Synchronized
    fun openSessionIfGenerationMatches(
        expectedAuthGeneration: Long,
        slotId: Int,
        dekBytes: ByteArray,
        isDecoyConfiguredInRealVault: Boolean = false
    ): SessionState.Unlocked? {
        if (authRequestGenerationCounter.get() != expectedAuthGeneration) {
            return null
        }
        val current = _sessionState.value
        if (current is SessionState.Unlocked) {
            current.wipeKeyMaterial()
        }
        authRequestGenerationCounter.incrementAndGet()
        val newId = generationCounter.incrementAndGet()
        val session = SessionState.Unlocked(
            sessionId = newId,
            slotId = slotId,
            internalDekBytes = dekBytes.copyOf(),
            isDecoyConfiguredInRealVault = if (slotId == REAL_SLOT) isDecoyConfiguredInRealVault else false
        )
        lastBackgroundedAtMonotonicMs = null
        filePickerGrantUntilMonotonicMs = 0L
        _sessionState.value = session
        return session
    }

    @Synchronized
    fun lockNow() {
        authRequestGenerationCounter.incrementAndGet()
        val current = _sessionState.value
        if (current is SessionState.Unlocked) {
            current.wipeKeyMaterial()
        }
        lastBackgroundedAtMonotonicMs = null
        filePickerGrantUntilMonotonicMs = 0L
        _sessionState.value = SessionState.Locked
    }

    @Synchronized
    fun onAppBackgrounded() {
        if (_sessionState.value is SessionState.Unlocked) {
            lastBackgroundedAtMonotonicMs = monotonicClockMs()
        }
    }

    @Synchronized
    fun onAppForegrounded(timeoutSeconds: Int): Boolean {
        val now = monotonicClockMs()
        val bgTime = lastBackgroundedAtMonotonicMs
        lastBackgroundedAtMonotonicMs = null

        // If file picker immunity is active, do not lock due to background timeout
        if (now <= filePickerGrantUntilMonotonicMs) {
            return false
        }
        filePickerGrantUntilMonotonicMs = 0L

        if (bgTime != null && timeoutSeconds >= 0) {
            val elapsedMs = now - bgTime
            val thresholdMs = timeoutSeconds * 1000L
            if (elapsedMs >= thresholdMs) {
                lockNow()
                return true
            }
        }
        return false
    }

    @Synchronized
    fun grantFilePickerImmunity() {
        filePickerGrantUntilMonotonicMs = monotonicClockMs() + FILE_PICKER_GRANT_WINDOW_MS
    }

    @Synchronized
    fun clearFilePickerImmunity() {
        filePickerGrantUntilMonotonicMs = 0L
    }

    fun isUnlocked(): Boolean {
        val s = _sessionState.value
        return s is SessionState.Unlocked && !s.isWiped
    }

    fun getActiveSlotIdOrNull(): Int? {
        val s = _sessionState.value
        return if (s is SessionState.Unlocked && !s.isWiped) s.slotId else null
    }

    fun getCurrentSessionId(): Long? {
        val s = _sessionState.value
        return if (s is SessionState.Unlocked && !s.isWiped) s.sessionId else null
    }

    fun requireUnlockedSession(): SessionState.Unlocked {
        val s = _sessionState.value
        check(s is SessionState.Unlocked && !s.isWiped) {
            "Vault operation rejected: Vault is locked."
        }
        return s
    }

    fun getActiveSession(): SessionState.Unlocked? {
        val s = _sessionState.value
        return if (s is SessionState.Unlocked && !s.isWiped) s else null
    }
}
