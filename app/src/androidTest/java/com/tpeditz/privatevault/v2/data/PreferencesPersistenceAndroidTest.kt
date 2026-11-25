package com.tpeditz.privatevault.v2.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesPersistenceAndroidTest {

    @Test
    fun testSecurityAndCloudPreferencesRoundTripPersistence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = AppPreferencesRepository(context)

        repo.setScreenshotProtectionEnabled(false)
        repo.setLockOnScreenOff(false)
        repo.setAutoLockTimeoutSeconds(120)
        repo.setClipboardTtlSeconds(45)
        repo.setCloudBackupFrequency(CloudBackupFrequency.DAILY)
        repo.setCloudBackupWifiOnly(false)
        repo.setConnectedGoogleAccount("user@example.com")

        assertFalse(repo.screenshotProtectionEnabled.first())
        assertFalse(repo.lockOnScreenOff.first())
        assertEquals(120, repo.autoLockTimeoutSeconds.first())
        assertEquals(45, repo.clipboardTtlSeconds.first())
        assertEquals(CloudBackupFrequency.DAILY, repo.cloudBackupFrequency.first())
        assertFalse(repo.cloudBackupWifiOnly.first())
        assertEquals("user@example.com", repo.connectedGoogleAccount.first())

        // Restore secure defaults after test
        repo.setScreenshotProtectionEnabled(true)
        repo.setLockOnScreenOff(true)
        repo.setAutoLockTimeoutSeconds(60)
        repo.setClipboardTtlSeconds(30)
        repo.setCloudBackupFrequency(CloudBackupFrequency.WEEKLY)
        repo.setCloudBackupWifiOnly(true)
        repo.setConnectedGoogleAccount("")
        assertTrue(repo.screenshotProtectionEnabled.first())
    }
}
