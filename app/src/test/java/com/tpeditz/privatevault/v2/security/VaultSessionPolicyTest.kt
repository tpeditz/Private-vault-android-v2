package com.tpeditz.privatevault.v2.security

import org.junit.Assert.*
import org.junit.Test

class VaultSessionPolicyTest {
    @Test
    fun pickerPermissionIsLimitedToOneRoundTrip() {
        var now = 100L
        val manager = VaultSessionManager { now }
        manager.openSession(VaultSessionManager.REAL_SLOT, ByteArray(32))
        manager.grantFilePickerImmunity()
        manager.onAppBackgrounded()
        now += 1000L
        assertFalse(manager.onAppForegrounded(0))
        assertTrue(manager.isUnlocked())
        manager.onAppBackgrounded()
        now += 1000L
        assertTrue(manager.onAppForegrounded(0))
        assertFalse(manager.isUnlocked())
    }

    @Test
    fun publishingRequiresCurrentSession() {
        val manager = VaultSessionManager { 100L }
        val session = manager.openSession(VaultSessionManager.REAL_SLOT, ByteArray(32))
        var published = false
        assertTrue(manager.runIfCurrentSession(session.sessionId) { published = true })
        assertTrue(published)
        manager.lockNow()
        published = false
        assertFalse(manager.runIfCurrentSession(session.sessionId) { published = true })
        assertFalse(published)
    }
}
