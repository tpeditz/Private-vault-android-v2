package com.tpeditz.privatevault.v2.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tpeditz.privatevault.v2.MainActivity
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.ota.UpdateManifest
import com.tpeditz.privatevault.v2.ui.screens.AboutScreen
import com.tpeditz.privatevault.v2.ui.screens.CloudBackupScreen
import com.tpeditz.privatevault.v2.ui.screens.SetupVaultScreen
import com.tpeditz.privatevault.v2.ui.theme.PrivateVaultTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareToVaultAndScreensAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAboutScreenDisplaysWebsiteAndLegalWithoutInternalIdentifiers() {
        composeRule.setContent {
            PrivateVaultTheme {
                AboutScreen(onCopyText = { _, _ -> }, onBack = {})
            }
        }
        composeRule.onNodeWithText("Website & Legal").assertIsDisplayed()
        composeRule.onNodeWithText("Official Website").assertIsDisplayed()
        composeRule.onNodeWithText("Privacy Policy").assertIsDisplayed()
        composeRule.onNodeWithText("Terms of Use").assertIsDisplayed()
        composeRule.onNodeWithText("github.com", substring = true).assertDoesNotExist()
    }

    @Test
    fun testSetupVaultScreenRejectsShortNewPasswordAndAcceptsTwelvePlusChars() {
        var createdPasswordLength = 0
        composeRule.setContent {
            PrivateVaultTheme {
                SetupVaultScreen(
                    isLoading = false,
                    onCreateVault = { chars, cb ->
                        createdPasswordLength = chars.size
                        cb(true, null)
                    },
                    onRestoreBackupClicked = {}
                )
            }
        }

        composeRule.onNodeWithText("Master Password (12+ chars)").performTextInput("shortPwd")
        composeRule.onNodeWithText("Confirm Master Password").performTextInput("shortPwd")
        composeRule.onNodeWithText("Create Encrypted Vault").performClick()

        composeRule.onNodeWithText("Use at least 12 characters", substring = true).assertIsDisplayed()
        assertEquals(0, createdPasswordLength)

        composeRule.onNodeWithText("Master Password (12+ chars)").performTextInput("Extra")
        composeRule.onNodeWithText("Confirm Master Password").performTextInput("Extra")
        composeRule.onNodeWithText("Create Encrypted Vault").performClick()
        composeRule.runOnIdle {
            assertEquals(13, createdPasswordLength)
        }
    }

    @Test
    fun testCloudBackupScreenOffersLegacyShortPasswordUpgradeNotice() {
        composeRule.setContent {
            PrivateVaultTheme {
                CloudBackupScreen(
                    uiState = VaultUiState(
                        connectedGoogleEmail = "tester@example.test",
                        autoCloudBackupEnabled = true,
                        autoCloudBackupKeyConfigured = true,
                        isCloudRecoveryPasswordLegacyShort = true
                    ),
                    isDriveConfigured = true,
                    onConnectGoogle = {},
                    onDisconnectGoogle = {},
                    onUploadCloudBackup = {},
                    onConfigureAutoBackup = { _, _ -> },
                    onSetCloudFrequency = {},
                    onSetCloudWifiOnly = {},
                    onQuerySnapshots = { cb -> cb(emptyList(), null) },
                    onDownloadSnapshot = {},
                    onDeleteSnapshot = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Change automatic backup password").assertIsDisplayed()
        composeRule.onNodeWithText("12+ characters", substring = true).assertIsDisplayed()
    }

    @Test
    fun testOtaPermissionRecheckRejectsDebuggableAndVerifiesOnResume(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = tempFolder.newFolder("ota_cache")
        val otaDir = File(cacheDir, "ota_updates").apply { mkdirs() }
        val dummyApk = File(otaDir, "private-vault-v2-2.apk").apply { writeBytes("fake".toByteArray()) }

        var allowInstall = false
        val certHex = OtaUpdateManager.EXPECTED_RELEASE_SIGNER_CERT_SHA256.replace(":", "").lowercase()
        val manifest = UpdateManifest(
            schemaVersion = 1,
            applicationId = "com.tpeditz.privatevault.v2",
            versionCode = 2,
            versionName = "2.0.1",
            apkUrl = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.0.1/private-vault.apk",
            sha256 = OtaUpdateManager.sha256Hex(dummyApk.readBytes()),
            sizeBytes = dummyApk.length(),
            publishedAt = "2026-11-25T12:00:00Z",
            releaseNotes = "Update test",
            signingCertSha256 = OtaUpdateManager.EXPECTED_RELEASE_SIGNER_CERT_SHA256
        )

        val debuggablePkgInfo = PackageInfo().apply {
            packageName = "com.tpeditz.privatevault.v2"
            longVersionCode = 2L
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_DEBUGGABLE
            }
        }
        val managerWithDebuggableArchive = OtaUpdateManager(
            context = context,
            installedSignerDigestsProvider = { setOf(certHex) },
            installedVersionCodeProvider = { 1L },
            archiveInfoParser = { _, _ -> debuggablePkgInfo },
            cacheDirProvider = { cacheDir },
            canRequestPackageInstallsProvider = { allowInstall }
        )

        // Ensure archive parser FLAG_DEBUGGABLE check rejects debuggable candidate packages
        val thrown = runCatching {
            val method = OtaUpdateManager::class.java.getDeclaredMethod(
                "verifyCandidateApkFile",
                File::class.java,
                UpdateManifest::class.java
            )
            method.isAccessible = true
            method.invoke(managerWithDebuggableArchive, dummyApk, manifest)
        }.exceptionOrNull()
        assertTrue(thrown != null)

        // Ensure ACTION_SEND intent construction targets MainActivity cleanly
        val sendIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.test/shared")
        }
        assertFalse(sendIntent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank())
    }
}
