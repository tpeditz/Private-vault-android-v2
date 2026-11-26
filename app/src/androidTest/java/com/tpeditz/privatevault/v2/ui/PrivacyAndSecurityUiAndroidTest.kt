package com.tpeditz.privatevault.v2.ui

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.data.BackupExportScope
import com.tpeditz.privatevault.v2.data.BackupPayload
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.data.NoteBodyFormat
import com.tpeditz.privatevault.v2.data.NoteRecord
import com.tpeditz.privatevault.v2.data.PasswordHistoryEntry
import com.tpeditz.privatevault.v2.data.dataStore
import com.tpeditz.privatevault.v2.ui.screens.AboutScreen
import com.tpeditz.privatevault.v2.ui.screens.BackupRestoreScreen
import com.tpeditz.privatevault.v2.ui.screens.CloudBackupScreen
import com.tpeditz.privatevault.v2.ui.screens.NoteEditorScreen
import com.tpeditz.privatevault.v2.ui.screens.PasswordEditorScreen
import com.tpeditz.privatevault.v2.ui.screens.PrivacyDashboardScreen
import com.tpeditz.privatevault.v2.ui.screens.SecuritySettingsScreen
import com.tpeditz.privatevault.v2.ui.theme.PrivateVaultTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
                            isBiometricEnabled = true
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
        composeRule.onNodeWithText("Autofill", substring = true, ignoreCase = true).assertDoesNotExist()

        composeRule.onNodeWithText("Biometric Unlock").performClick()
        composeRule.runOnIdle {
            assertEquals(SecondaryDestination.SecuritySettings, navigatedDestination)
        }
    }

    @Test
    fun testNoAutofillServiceRegisteredAndLegacyPreferenceUpgradeSafe() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val autofillIntent = Intent("android.service.autofill.AutofillService").setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(autofillIntent, 0)
        assertTrue("Expected zero Autofill services in upgraded APK", services.isEmpty())

        // Simulate legacy V2.0.3 DataStore containing old autofill_enabled = true key
        context.dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("autofill_enabled")] = true
        }
        val repo = AppPreferencesRepository(context)
        repo.setAutoLockTimeoutSeconds(300)
        assertEquals(300, repo.autoLockTimeoutSeconds.first())
        assertTrue(repo.screenshotProtectionEnabled.first())
        repo.setAutoLockTimeoutSeconds(60)
    }

    @Test
    fun testPasswordHistoryMasksPasswordsByDefaultWithRevealToggle() {
        val cred = CredentialRecord(
            id = "cred-hist-1",
            serviceName = "Example Portal",
            username = "user@example.test",
            password = "CurrentStrong#Password99",
            passwordHistory = listOf(
                PasswordHistoryEntry(id = "hist-1", password = "Previous!Secret#88", changedAtEpochMs = 1_700_000_000_000L)
            )
        )
        composeRule.setContent {
            PrivateVaultTheme {
                PasswordEditorScreen(
                    credentialId = cred.id,
                    uiState = VaultUiState(credentials = listOf(cred)),
                    onSaveCredential = {},
                    onDeleteCredential = {},
                    onRestorePassword = { _, _ -> },
                    onCopyPassword = { _, _ -> },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Website or App URL (optional)").assertIsDisplayed()
        composeRule.onNodeWithText("Website URL (for Autofill)").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Password History").performClick()

        // History password must stay masked until explicitly revealed
        composeRule.onNodeWithText("••••••••••••").assertIsDisplayed()
        composeRule.onNodeWithText("Previous!Secret#88").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show password").performClick()
        composeRule.onNodeWithText("Previous!Secret#88").assertIsDisplayed()
    }

    @Test
    fun testNoteEditorDoesNotAutoSaveAndCommitsSingleRecordOnExplicitSave() {
        val savedRecords = mutableListOf<NoteRecord>()
        var backCalls = 0
        composeRule.setContent {
            PrivateVaultTheme {
                NoteEditorScreen(
                    noteId = null,
                    uiState = VaultUiState(),
                    onSaveNote = { rec, cb ->
                        savedRecords.add(rec)
                        cb(true, null)
                    },
                    onDeleteNote = {},
                    onBack = { backCalls++ }
                )
            }
        }

        // Typing must not trigger auto-save
        composeRule.onNodeWithText("Title").performTextInput("Explicit Title")
        composeRule.onNodeWithText("Note Body").performTextInput("Explicit Body")
        composeRule.mainClock.advanceTimeBy(1500L)
        composeRule.runOnIdle {
            assertEquals(0, savedRecords.size)
            assertEquals(0, backCalls)
        }

        // Explicit Save Note saves once and calls onBack
        composeRule.onNodeWithText("Save Note").performClick()
        composeRule.runOnIdle {
            assertEquals(1, savedRecords.size)
            assertEquals("Explicit Title", savedRecords[0].title)
            assertEquals("Explicit Body", savedRecords[0].body)
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun testNoteEditorShowsUnsavedDialogAndPreservesDraftOnFailure() {
        val savedRecords = mutableListOf<NoteRecord>()
        var backCalls = 0
        var shouldFailSave = true
        var pendingCallback: ((Boolean, String?) -> Unit)? = null

        composeRule.setContent {
            PrivateVaultTheme {
                NoteEditorScreen(
                    noteId = null,
                    uiState = VaultUiState(),
                    onSaveNote = { rec, cb ->
                        savedRecords.add(rec)
                        if (shouldFailSave) {
                            cb(false, "Storage unavailable")
                        } else {
                            pendingCallback = cb
                        }
                    },
                    onDeleteNote = {},
                    onBack = { backCalls++ }
                )
            }
        }

        // Validation rejects completely blank note
        composeRule.onNodeWithText("Save Note").performClick()
        composeRule.onNodeWithText("Enter a note title, body, or checklist item before saving.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, savedRecords.size) }

        // Back on modified note triggers Save changes? dialog
        composeRule.onNodeWithText("Title").performTextInput("Draft Title")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Save changes?").assertIsDisplayed()

        // Keep editing keeps editor open
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithText("Save changes?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, backCalls) }

        // Failing save preserves draft and shows inline error without leaving
        composeRule.onNodeWithText("Save Note").performClick()
        composeRule.onNodeWithText("Storage unavailable").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, backCalls) }

        // Pending save disables duplicate Save taps until callback completes
        shouldFailSave = false
        composeRule.onNodeWithText("Save Note").performClick()
        composeRule.onNodeWithText("Saving...").assertIsNotEnabled()
        composeRule.runOnIdle {
            pendingCallback?.invoke(true, null)
            assertEquals(1, backCalls)
            assertEquals(savedRecords[0].id, savedRecords[1].id)
        }
    }

    @Test
    fun testNoteEditorPreservesPendingChecklistInputAcrossSaveAndUnsavedDialog() {
        val savedRecords = mutableListOf<NoteRecord>()
        var backCalls = 0

        composeRule.setContent {
            PrivateVaultTheme {
                NoteEditorScreen(
                    noteId = null,
                    uiState = VaultUiState(),
                    onSaveNote = { rec, cb ->
                        savedRecords.add(rec)
                        cb(true, null)
                    },
                    onDeleteNote = {},
                    onBack = { backCalls++ }
                )
            }
        }

        // Switch to Checklist mode, type pending checklist input without tapping '+', then tap Save Note
        composeRule.onNodeWithText("Checklist").performClick()
        composeRule.onNodeWithText("Title").performTextInput("Groceries")
        composeRule.onNodeWithText("Add checklist item...").performTextInput("Fresh milk")
        composeRule.onNodeWithText("Save Note").performClick()

        composeRule.runOnIdle {
            assertEquals(1, savedRecords.size)
            assertEquals(NoteBodyFormat.CHECKLIST, savedRecords[0].format)
            assertEquals(1, savedRecords[0].checklistItems.size)
            assertEquals("Fresh milk", savedRecords[0].checklistItems[0].text)
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun testNoteEditorDialogSaveAlsoIncludesPendingChecklistInput() {
        val savedRecords = mutableListOf<NoteRecord>()
        var backCalls = 0

        composeRule.setContent {
            PrivateVaultTheme {
                NoteEditorScreen(
                    noteId = null,
                    uiState = VaultUiState(),
                    onSaveNote = { rec, cb ->
                        savedRecords.add(rec)
                        cb(true, null)
                    },
                    onDeleteNote = {},
                    onBack = { backCalls++ }
                )
            }
        }

        composeRule.onNodeWithText("Checklist").performClick()
        composeRule.onNodeWithText("Add checklist item...").performTextInput("Pack passport")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Save changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle {
            assertEquals(1, savedRecords.size)
            assertEquals("Pack passport", savedRecords[0].checklistItems.single().text)
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun testBackupRestoreScreenShowsScopeAndDisablesReplaceForPartialBackup() {
        val partialPayload = BackupPayload(
            exportedAtEpochMs = 1_700_000_000_000L,
            appVersionName = "2.0.4",
            items = listOf(NoteRecord(id = "n-scope-1", title = "Scoped Note", body = "Hello")),
            exportScope = BackupExportScope.NOTES_ONLY,
            isExportScopeKnown = true
        )
        composeRule.setContent {
            PrivateVaultTheme {
                BackupRestoreScreen(
                    uiState = VaultUiState(pendingRestorePayload = partialPayload),
                    onTriggerCreateBackupDocument = { _, _ -> },
                    onTriggerOpenBackupDocument = {},
                    onConfirmRestore = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Backup Scope: Notes Only").assertIsDisplayed()
        composeRule.onNodeWithText("Merge (Keep Newest)").assertIsEnabled()
        composeRule.onNodeWithText("Replace Active").assertIsNotEnabled()
    }

    @Test
    fun testBackupRestoreScreenMarksUnknownLegacyScopeAndRecommendsMerge() {
        val unknownScopePayload = BackupPayload(
            exportedAtEpochMs = 1_680_000_000_000L,
            appVersionName = "1.0.4",
            items = listOf(NoteRecord(id = "n-old-1", title = "Old Note", body = "Body")),
            exportScope = BackupExportScope.ALL_DATA,
            isExportScopeKnown = false
        )
        composeRule.setContent {
            PrivateVaultTheme {
                BackupRestoreScreen(
                    uiState = VaultUiState(pendingRestorePayload = unknownScopePayload),
                    onTriggerCreateBackupDocument = { _, _ -> },
                    onTriggerOpenBackupDocument = {},
                    onConfirmRestore = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Backup Scope: Backup scope unknown").assertIsDisplayed()
        composeRule.onNodeWithText("Merge (Recommended)").assertIsEnabled()
        composeRule.onNodeWithText("Replace Active").assertIsEnabled()
    }
}
