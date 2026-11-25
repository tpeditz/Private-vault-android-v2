package com.tpeditz.privatevault.v2.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.ui.screens.AboutScreen
import com.tpeditz.privatevault.v2.ui.screens.CloudBackupScreen
import com.tpeditz.privatevault.v2.ui.screens.PrivacyDashboardScreen
import com.tpeditz.privatevault.v2.ui.screens.SecuritySettingsScreen
import com.tpeditz.privatevault.v2.ui.theme.PrivateVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyAndSecurityUiAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testAboutScreenShowsConsumerContentOnly() {
        composeRule.setContent {
            PrivateVaultTheme {
                AboutScreen(onCopyText = { _, _ -> }, onBack = {})
            }
        }
        composeRule.onNodeWithText("Private Vault").assertIsDisplayed()
        composeRule.onNodeWithText("Developed by Tp Editz").assertIsDisplayed()
        composeRule.onNodeWithText("@Tp_Editz").assertIsDisplayed()
        composeRule.onNodeWithText("@tp_ediitz").assertIsDisplayed()

        // Ensure developer / cryptographic signing internals do not leak to consumer About screen
        composeRule.onNodeWithText("Release Signing Identity", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("SHA-256", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("com.tpeditz.privatevault.v2", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("github.com", substring = true).assertDoesNotExist()
    }

    @Test
    fun testSecuritySettingsBindsAllTogglesAndChipsReactively() {
        var currentState by mutableStateOf(
            VaultUiState(
                lockOnScreenOff = true,
                screenshotProtectionEnabled = true,
                clipboardTtlSeconds = 30,
                autoLockTimeoutSeconds = 60,
                isBiometricsConfigured = false
            )
        )

        composeRule.setContent {
            PrivateVaultTheme {
                SecuritySettingsScreen(
                    uiState = currentState,
                    onSetScreenshotProtection = { currentState = currentState.copy(screenshotProtectionEnabled = it) },
                    onSetLockOnScreenOff = { currentState = currentState.copy(lockOnScreenOff = it) },
                    onSetAutoLockTimeout = { currentState = currentState.copy(autoLockTimeoutSeconds = it) },
                    onSetClipboardTtl = { currentState = currentState.copy(clipboardTtlSeconds = it) },
                    onEnableBiometrics = { currentState = currentState.copy(isBiometricsConfigured = true) },
                    onDisableBiometrics = { currentState = currentState.copy(isBiometricsConfigured = false) },
                    onProvisionDecoy = { _, _ -> },
                    onDisableDecoy = {},
                    onBack = {}
                )
            }
        }

        // Toggle Lock on Screen Off -> must update bound state to false
        composeRule.onNodeWithText("Lock on Screen Off").performClick()
        composeRule.runOnIdle {
            assertFalse(currentState.lockOnScreenOff)
        }

        // Toggle Biometric Unlock -> calls enable callback
        composeRule.onNodeWithText("Biometric Unlock").performClick()
        composeRule.runOnIdle {
            assertTrue(currentState.isBiometricsConfigured)
        }

        // Select 60s clipboard timer chip
        composeRule.onNodeWithText("60s").performClick()
        composeRule.runOnIdle {
            assertEquals(60, currentState.clipboardTtlSeconds)
        }

        // Select 5m auto-lock chip
        composeRule.onNodeWithText("5m").performClick()
        composeRule.runOnIdle {
            assertEquals(300, currentState.autoLockTimeoutSeconds)
        }
    }

    @Test
    fun testCloudBackupScreenShowsConnectedAndErrorRetryStates() {
        var retriedQueryCount = 0
        composeRule.setContent {
            PrivateVaultTheme {
                CloudBackupScreen(
                    uiState = VaultUiState(
                        connectedGoogleEmail = "tester@example.com",
                        cloudBackupFrequency = CloudBackupFrequency.WEEKLY,
                        cloudBackupWifiOnly = true
                    ),
                    isDriveConfigured = true,
                    onConnectGoogle = {},
                    onDisconnectGoogle = {},
                    onUploadCloudBackup = {},
                    onConfigureAutoBackup = { _, _ -> },
                    onSetCloudFrequency = {},
                    onSetCloudWifiOnly = {},
                    onQuerySnapshots = { cb ->
                        retriedQueryCount++
                        cb(emptyList(), "Network unavailable")
                    },
                    onDownloadSnapshot = {},
                    onDeleteSnapshot = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Connected as tester@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Could not load backups").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle {
            assertTrue(retriedQueryCount >= 2)
        }
    }

    @Test
    fun testPrivacyDashboardAvoidsTechnicalJargon() {
        var navigatedDestination: SecondaryDestination? = null
        composeRule.setContent {
            PrivateVaultTheme {
                PrivacyDashboardScreen(
                    uiState = VaultUiState(
                        privacyMetrics = PrivacyHealthMetrics(
                            totalItemsCount = 3,
                            averagePasswordScore = 85,
                            isScreenshotProtected = true,
                            isBiometricEnabled = true,
                            isAutofillActive = false
                        )
                    ),
                    onNavigateToTab = {},
                    onNavigateToSecondary = { navigatedDestination = it },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Privacy Health").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshot & App Switcher Protection").assertIsDisplayed()
        composeRule.onNodeWithText("FLAG_SECURE", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Android Autofill Service").performClick()
        composeRule.runOnIdle {
            assertEquals(SecondaryDestination.AutofillSettings, navigatedDestination)
        }
    }
}
