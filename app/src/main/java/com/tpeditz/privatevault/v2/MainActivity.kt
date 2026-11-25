package com.tpeditz.privatevault.v2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.io.File
import java.io.FileInputStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tpeditz.privatevault.v2.data.BackupExportScope
import com.tpeditz.privatevault.v2.data.RestoreMode
import com.tpeditz.privatevault.v2.security.BiometricKeystoreManager
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordPolicy
import com.tpeditz.privatevault.v2.sync.GoogleDriveAuthHelper
import com.tpeditz.privatevault.v2.ui.PrimaryTab
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultViewModel
import com.tpeditz.privatevault.v2.ui.screens.AboutScreen
import com.tpeditz.privatevault.v2.ui.screens.AutofillSettingsScreen
import com.tpeditz.privatevault.v2.ui.screens.BackupRestoreScreen
import com.tpeditz.privatevault.v2.ui.screens.CloudBackupScreen
import com.tpeditz.privatevault.v2.ui.screens.DocumentViewerScreen
import com.tpeditz.privatevault.v2.ui.screens.HomeScreen
import com.tpeditz.privatevault.v2.ui.screens.LinkEditorScreen
import com.tpeditz.privatevault.v2.ui.screens.MediaScreen
import com.tpeditz.privatevault.v2.ui.screens.MediaViewerScreen
import com.tpeditz.privatevault.v2.ui.screens.NoteEditorScreen
import com.tpeditz.privatevault.v2.ui.screens.NotesScreen
import com.tpeditz.privatevault.v2.ui.screens.OtaUpdatesScreen
import com.tpeditz.privatevault.v2.ui.screens.PasswordEditorScreen
import com.tpeditz.privatevault.v2.ui.screens.PasswordsScreen
import com.tpeditz.privatevault.v2.ui.screens.PrivacyDashboardScreen
import com.tpeditz.privatevault.v2.ui.screens.SecuritySettingsScreen
import com.tpeditz.privatevault.v2.ui.screens.SettingsScreen
import com.tpeditz.privatevault.v2.ui.screens.SetupVaultScreen
import com.tpeditz.privatevault.v2.ui.screens.ToolsScreen
import com.tpeditz.privatevault.v2.ui.screens.UnlockVaultScreen
import com.tpeditz.privatevault.v2.ui.theme.PrivateVaultTheme
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels {
        VaultViewModel.Factory(applicationContext)
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                viewModel.onScreenTurnedOff()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enforce FLAG_SECURE by default before window renders
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Handle Share-In intent if present
        handleIncomingSendIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                        Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                        Lifecycle.Event.ON_RESUME -> viewModel.refreshSystemSecurityStatus()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Show clipboard countdown in toast or snackbar
            LaunchedEffect(uiState.clipboardCountdownSeconds) {
                val sec = uiState.clipboardCountdownSeconds
                if (sec != null && sec > 0 && sec % 10 == 0) {
                    Toast.makeText(this@MainActivity, "Clipboard will clear in ${sec}s", Toast.LENGTH_SHORT).show()
                }
            }

            // Dynamically synchronize FLAG_SECURE with screenshot protection and lock state
            LaunchedEffect(uiState.screenshotProtectionEnabled, uiState.isUnlocked) {
                if (!uiState.isUnlocked || uiState.screenshotProtectionEnabled) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // SAF Launchers & Dialog State
            var pendingBackupScope by remember { mutableStateOf<BackupExportScope?>(null) }
            var pendingBackupPassword by remember { mutableStateOf<CharArray?>(null) }
            var pendingRestorePassword by remember { mutableStateOf<CharArray?>(null) }
            var lastRestoreBackupUri by remember { mutableStateOf<Uri?>(null) }
            var lastRestoreCloudFile by remember { mutableStateOf<File?>(null) }

            var showFirstLaunchRestoreDialog by remember { mutableStateOf(false) }
            var firstLaunchRestorePassword by remember { mutableStateOf("") }
            var showRetryRestorePasswordDialog by remember { mutableStateOf(false) }
            var retryRestorePassword by remember { mutableStateOf("") }
            var showCloudBackupPasswordDialog by remember { mutableStateOf(false) }
            var cloudBackupPassword by remember { mutableStateOf("") }
            var confirmCloudBackupPassword by remember { mutableStateOf("") }
            var showCloudRestorePasswordDialog by remember { mutableStateOf(false) }
            var cloudRestorePassword by remember { mutableStateOf("") }
            var pendingDownloadSnapFileId by remember { mutableStateOf<String?>(null) }
            var pendingConnectedEmailForConsent by remember { mutableStateOf("") }

            fun inspectLocalUriWithPassword(uri: Uri, passwordChars: CharArray) {
                viewModel.grantFilePickerImmunity()
                val ins = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                if (ins != null) {
                    viewModel.inspectBackupForRestore(ins, passwordChars) { _, _ ->
                        java.util.Arrays.fill(passwordChars, '\u0000')
                    }
                } else {
                    java.util.Arrays.fill(passwordChars, '\u0000')
                    scope.launch {
                        snackbarHostState.showSnackbar("Private Vault couldn't open that file. Choose the backup again.")
                    }
                }
            }

            fun inspectCloudFileWithPassword(file: File, passwordChars: CharArray) {
                try {
                    val ins = FileInputStream(file)
                    viewModel.inspectBackupForRestore(ins, passwordChars) { payload, _ ->
                        java.util.Arrays.fill(passwordChars, '\u0000')
                        runCatching { ins.close() }
                        if (payload != null) {
                            runCatching { file.delete() }
                            if (lastRestoreCloudFile == file) {
                                lastRestoreCloudFile = null
                            }
                            viewModel.navigateToSecondary(SecondaryDestination.BackupAndRestore)
                        }
                    }
                } catch (_: Exception) {
                    java.util.Arrays.fill(passwordChars, '\u0000')
                    scope.launch {
                        snackbarHostState.showSnackbar("Private Vault couldn't open the downloaded backup.")
                    }
                }
            }

            val createBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri: Uri? ->
                val activeScope = pendingBackupScope
                val activePwd = pendingBackupPassword
                pendingBackupScope = null
                pendingBackupPassword = null
                if (uri != null && activeScope != null && activePwd != null) {
                    viewModel.grantFilePickerImmunity()
                    val os = runCatching { contentResolver.openOutputStream(uri) }.getOrNull()
                    if (os != null) {
                        viewModel.exportStreamingBackup(
                            scope = activeScope,
                            passwordChars = activePwd,
                            outputStream = os,
                            onProgress = { _, _ -> },
                            onComplete = { success, err ->
                                java.util.Arrays.fill(activePwd, '\u0000')
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (success) "Backup saved successfully"
                                        else err ?: "Could not create backup."
                                    )
                                }
                            }
                        )
                    } else {
                        java.util.Arrays.fill(activePwd, '\u0000')
                        scope.launch {
                            snackbarHostState.showSnackbar("Private Vault couldn't write to the chosen file.")
                        }
                    }
                } else if (activePwd != null) {
                    java.util.Arrays.fill(activePwd, '\u0000')
                }
            }

            val openBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                val activePwd = pendingRestorePassword
                pendingRestorePassword = null
                if (uri != null && activePwd != null) {
                    lastRestoreCloudFile?.let { runCatching { it.delete() } }
                    lastRestoreCloudFile = null
                    lastRestoreBackupUri = uri
                    inspectLocalUriWithPassword(uri, activePwd)
                } else if (activePwd != null) {
                    java.util.Arrays.fill(activePwd, '\u0000')
                }
            }

            val pickCsvLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    viewModel.grantFilePickerImmunity()
                    val ins = contentResolver.openInputStream(uri)
                    if (ins != null) {
                        viewModel.importCsv(ins) { imported, skipped ->
                            scope.launch {
                                snackbarHostState.showSnackbar("Imported $imported credentials ($skipped skipped)")
                            }
                        }
                    }
                }
            }

            val pickVisualMediaLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    viewModel.grantFilePickerImmunity()
                    viewModel.importVisualMedia(uris) { imported, failed ->
                        scope.launch {
                            val msg = "Imported $imported media item(s)" + if (failed > 0) " ($failed failed)" else ""
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                }
            }

            val pickDocumentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    viewModel.grantFilePickerImmunity()
                    viewModel.importDocuments(uris) { imported, failed ->
                        scope.launch {
                            val msg = "Imported $imported document(s)" + if (failed > 0) " ($failed failed)" else ""
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                }
            }

            val driveConsentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                val resolvedEmail = pendingConnectedEmailForConsent.ifBlank { uiState.connectedGoogleEmail }
                when (val authState = GoogleDriveAuthHelper.parseAuthorizationConsentResult(this@MainActivity, result.data, resolvedEmail)) {
                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> {
                        scope.launch {
                            snackbarHostState.showSnackbar("Google Drive access connected")
                        }
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                        scope.launch {
                            snackbarHostState.showSnackbar(authState.userMessage)
                        }
                    }
                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> Unit
                }
            }

            PrivateVaultTheme(themePreference = uiState.themePreference) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            if (uiState.isUnlocked && uiState.currentSecondary is SecondaryDestination.None) {
                                NavigationBar {
                                    PrimaryTab.values().forEach { tab ->
                                        val icon = when (tab) {
                                            PrimaryTab.HOME -> Icons.Default.Home
                                            PrimaryTab.NOTES -> Icons.Default.Description
                                            PrimaryTab.PASSWORDS -> Icons.Default.Key
                                            PrimaryTab.MEDIA -> Icons.Default.PhotoLibrary
                                            PrimaryTab.SETTINGS -> Icons.Default.Settings
                                        }
                                        NavigationBarItem(
                                            selected = uiState.currentTab == tab,
                                            onClick = { viewModel.navigateToTab(tab) },
                                            icon = { Icon(icon, contentDescription = tab.label) },
                                            label = { Text(tab.label) }
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (!uiState.isVaultInitialized) {
                                SetupVaultScreen(
                                    isLoading = uiState.isLoading,
                                    pendingRestorePayload = uiState.pendingRestorePayload,
                                    onCreateVault = { chars, cb -> viewModel.createVault(chars, cb) },
                                    onCreateVaultWithRestore = { chars, cb ->
                                        viewModel.createVaultAndCommitRestore(chars, RestoreMode.REPLACE_ACTIVE_VAULT, cb)
                                    },
                                    onRestoreBackupClicked = {
                                        firstLaunchRestorePassword = ""
                                        showFirstLaunchRestoreDialog = true
                                    },
                                    onCancelRestore = {
                                        viewModel.clearPendingRestore()
                                    }
                                )
                            } else if (!uiState.isUnlocked) {
                                UnlockVaultScreen(
                                    isLoading = uiState.isLoading,
                                    isBiometricAvailable = BiometricKeystoreManager.canAuthenticate(this@MainActivity) &&
                                        BiometricKeystoreManager.hasKeystoreKey(),
                                    onUnlockWithPassword = { chars, cb -> viewModel.unlockWithPassword(chars, cb) },
                                    onUnlockWithBiometric = {
                                        scope.launch {
                                            val iv = viewModel.getBiometricIvForUnlock()
                                            if (iv != null) {
                                                val cipher = BiometricKeystoreManager.getDecryptCipherForUnlock(iv)
                                                if (cipher != null) {
                                                    val prompt = BiometricPrompt(
                                                        this@MainActivity,
                                                        ContextCompat.getMainExecutor(this@MainActivity),
                                                        object : BiometricPrompt.AuthenticationCallback() {
                                                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                                val authCipher = result.cryptoObject?.cipher ?: cipher
                                                                viewModel.unlockWithBiometric(authCipher) { success, err ->
                                                                    if (!success) {
                                                                        scope.launch { snackbarHostState.showSnackbar(err ?: "Biometrics failed") }
                                                                    }
                                                                }
                                                            }
                                                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                                                scope.launch { snackbarHostState.showSnackbar(errString.toString()) }
                                                            }
                                                        }
                                                    )
                                                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                                        .setTitle("Unlock Private Vault")
                                                        .setSubtitle("Confirm your biometric identity")
                                                        .setNegativeButtonText("Use Password")
                                                        .build()
                                                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                                                } else {
                                                    snackbarHostState.showSnackbar("Biometric cipher unavailable")
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar("Biometrics not enrolled for Slot 0")
                                            }
                                        }
                                    }
                                )
                            } else {
                                // Vault is Unlocked: Handle Screen Routing
                                BackHandler(enabled = uiState.currentSecondary !is SecondaryDestination.None) {
                                    viewModel.navigateBack()
                                }

                                when (val sec = uiState.currentSecondary) {
                                    is SecondaryDestination.NoteEditor -> {
                                        NoteEditorScreen(
                                            noteId = sec.noteId,
                                            uiState = uiState,
                                            onSaveNote = { viewModel.saveNote(it) },
                                            onDeleteNote = { viewModel.deleteNote(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.PasswordEditor -> {
                                        PasswordEditorScreen(
                                            credentialId = sec.credentialId,
                                            uiState = uiState,
                                            onSaveCredential = { viewModel.saveCredential(it) },
                                            onDeleteCredential = { viewModel.deleteCredential(it) },
                                            onRestorePassword = { cid, hist -> viewModel.restorePreviousPassword(cid, hist) },
                                            onCopyPassword = { l, p -> viewModel.copySensitivePassword(l, p) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.MediaViewer -> {
                                        MediaViewerScreen(
                                            mediaId = sec.mediaId,
                                            viewModel = viewModel,
                                            mediaList = uiState.media,
                                            onDeleteMedia = { viewModel.deleteMedia(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.DocumentViewer -> {
                                        DocumentViewerScreen(
                                            documentId = sec.documentId,
                                            viewModel = viewModel,
                                            documentsList = uiState.documents,
                                            onDeleteDocument = { viewModel.deleteDocument(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.AutofillSettings -> {
                                        AutofillSettingsScreen(
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.LinkEditor -> {
                                        LinkEditorScreen(
                                            linkId = sec.linkId,
                                            uiState = uiState,
                                            onSaveLink = { viewModel.saveLink(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.PrivacyDashboard -> {
                                        PrivacyDashboardScreen(
                                            uiState = uiState,
                                            onNavigateToTab = { viewModel.navigateToTab(it) },
                                            onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.Tools -> {
                                        ToolsScreen(
                                            uiState = uiState,
                                            initialTab = 0,
                                            onCopyPassword = { l, p -> viewModel.copySensitivePassword(l, p) },
                                            onSaveAuthenticator = { viewModel.saveAuthenticator(it) },
                                            onDeleteAuthenticator = { viewModel.deleteAuthenticator(it) },
                                            onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.Authenticator -> {
                                        ToolsScreen(
                                            uiState = uiState,
                                            initialTab = 2,
                                            onCopyPassword = { l, p -> viewModel.copySensitivePassword(l, p) },
                                            onSaveAuthenticator = { viewModel.saveAuthenticator(it) },
                                            onDeleteAuthenticator = { viewModel.deleteAuthenticator(it) },
                                            onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.BackupAndRestore -> {
                                        BackupRestoreScreen(
                                            uiState = uiState,
                                            onTriggerCreateBackupDocument = { scopeVal, pwd ->
                                                pendingBackupScope = scopeVal
                                                pendingBackupPassword = pwd
                                                val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                                createBackupLauncher.launch("private_vault_backup_$df.pvault")
                                            },
                                            onTriggerOpenBackupDocument = { pwd ->
                                                pendingRestorePassword = pwd
                                                openBackupLauncher.launch(arrayOf("*/*"))
                                            },
                                            onConfirmRestore = { mode ->
                                                viewModel.confirmRestore(mode) { success ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(if (success) "Restore complete" else "Restore failed")
                                                    }
                                                }
                                            },
                                            onCancelOperation = {
                                                viewModel.cancelBackup()
                                                viewModel.cancelRestore()
                                            },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.CloudBackup -> {
                                        val consentCallback: (android.app.PendingIntent) -> Unit = { pi ->
                                            viewModel.grantFilePickerImmunity()
                                            pendingConnectedEmailForConsent = uiState.connectedGoogleEmail
                                            driveConsentLauncher.launch(IntentSenderRequest.Builder(pi).build())
                                        }
                                        CloudBackupScreen(
                                            uiState = uiState,
                                            isDriveConfigured = GoogleDriveAuthHelper.isGoogleDriveConfigured(),
                                            onConnectGoogle = {
                                                if (!GoogleDriveAuthHelper.isPlayServicesAvailable(this@MainActivity)) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Google Play Services is unavailable on this device.")
                                                    }
                                                } else {
                                                    viewModel.grantFilePickerImmunity()
                                                    scope.launch {
                                                        val signInRes = GoogleDriveAuthHelper.signInWithCredentialManager(this@MainActivity)
                                                        signInRes.onSuccess { email ->
                                                            pendingConnectedEmailForConsent = email
                                                            viewModel.onGoogleAccountConnected(email)
                                                            viewModel.authorizeGoogleDrive(email) { authState ->
                                                                when (authState) {
                                                                    is GoogleDriveAuthHelper.DriveAuthState.Authorized -> {
                                                                        scope.launch {
                                                                            snackbarHostState.showSnackbar("Connected Google account: $email")
                                                                        }
                                                                    }
                                                                    is GoogleDriveAuthHelper.DriveAuthState.NeedsConsent -> {
                                                                        viewModel.grantFilePickerImmunity()
                                                                        driveConsentLauncher.launch(IntentSenderRequest.Builder(authState.pendingIntent).build())
                                                                    }
                                                                    is GoogleDriveAuthHelper.DriveAuthState.Failed -> {
                                                                        scope.launch {
                                                                            snackbarHostState.showSnackbar(authState.userMessage)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }.onFailure { err ->
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(err.message ?: "Google Sign-In failed.")
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onDisconnectGoogle = {
                                                viewModel.disconnectGoogleAccount { ok, err ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) "Disconnected Google Drive"
                                                            else err ?: "Could not disconnect Google Drive"
                                                        )
                                                    }
                                                }
                                            },
                                            onUploadCloudBackup = { pwdChars ->
                                                if (pwdChars != null && pwdChars.isNotEmpty()) {
                                                    viewModel.uploadCloudBackup(
                                                        backupPasswordChars = pwdChars,
                                                        onConsentRequired = consentCallback
                                                    ) { success, err ->
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                if (success) "Cloud backup uploaded successfully"
                                                                else err ?: "Could not upload cloud backup."
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    cloudBackupPassword = ""
                                                    confirmCloudBackupPassword = ""
                                                    showCloudBackupPasswordDialog = true
                                                }
                                            },
                                            onConfigureAutoBackup = { enabled, recoveryPwd ->
                                                viewModel.configureAutomaticCloudBackup(
                                                    enabled = enabled,
                                                    recoveryPasswordChars = recoveryPwd,
                                                    onConsentRequired = consentCallback
                                                ) { ok, err ->
                                                    scope.launch {
                                                        val msg = when {
                                                            ok && enabled -> "Automatic backup enabled"
                                                            ok && !enabled -> "Automatic backup turned off"
                                                            else -> err ?: "Could not update automatic backup"
                                                        }
                                                        snackbarHostState.showSnackbar(msg)
                                                    }
                                                }
                                            },
                                            onSetCloudFrequency = { viewModel.setCloudBackupFrequency(it) },
                                            onSetCloudWifiOnly = { viewModel.setCloudBackupWifiOnly(it) },
                                            onQuerySnapshots = { callback ->
                                                viewModel.queryCloudSnapshots(onConsentRequired = consentCallback) { list, err ->
                                                    callback(list, err)
                                                }
                                            },
                                            onDownloadSnapshot = { snapFileId ->
                                                pendingDownloadSnapFileId = snapFileId
                                                cloudRestorePassword = ""
                                                showCloudRestorePasswordDialog = true
                                            },
                                            onDeleteSnapshot = { snapFileId ->
                                                viewModel.deleteCloudSnapshot(snapFileId) { success ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(if (success) "Backup deleted" else "Could not delete backup")
                                                    }
                                                }
                                            },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.SecuritySettings -> {
                                        SecuritySettingsScreen(
                                            uiState = uiState,
                                            onSetScreenshotProtection = { viewModel.setScreenshotProtection(it) },
                                            onSetLockOnScreenOff = { viewModel.setLockOnScreenOff(it) },
                                            onSetAutoLockTimeout = { viewModel.setAutoLockTimeout(it) },
                                            onSetClipboardTtl = { viewModel.setClipboardTtl(it) },
                                            onEnableBiometrics = {
                                                val cipher = BiometricKeystoreManager.getEncryptCipherForEnrollment()
                                                if (cipher != null) {
                                                    val prompt = BiometricPrompt(
                                                        this@MainActivity,
                                                        ContextCompat.getMainExecutor(this@MainActivity),
                                                        object : BiometricPrompt.AuthenticationCallback() {
                                                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                                val authCipher = result.cryptoObject?.cipher ?: cipher
                                                                scope.launch {
                                                                    val ok = viewModel.enrollBiometrics(authCipher)
                                                                    snackbarHostState.showSnackbar(if (ok) "Biometric quick-unlock enrolled" else "Enrollment failed")
                                                                }
                                                            }
                                                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                                                scope.launch { snackbarHostState.showSnackbar("Biometric error: $errString") }
                                                            }
                                                        }
                                                    )
                                                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                                        .setTitle("Enroll Biometric Quick-Unlock")
                                                        .setSubtitle("Confirm your biometric identity to protect Vault Slot 0")
                                                        .setNegativeButtonText("Cancel")
                                                        .build()
                                                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                                                } else {
                                                    scope.launch { snackbarHostState.showSnackbar("Biometric enrollment cipher unavailable") }
                                                }
                                            },
                                            onDisableBiometrics = { viewModel.disableBiometrics() },
                                            onProvisionDecoy = { primaryPwd, decoyPwd ->
                                                viewModel.provisionDecoy(primaryPwd, decoyPwd) { ok, err ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(if (ok) "Decoy vault created successfully" else "Failed to create decoy: $err")
                                                    }
                                                }
                                            },
                                            onDisableDecoy = { primaryPwd ->
                                                viewModel.disableDecoy(primaryPwd) { ok ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(if (ok) "Decoy vault disabled" else "Failed to disable decoy: incorrect primary password")
                                                    }
                                                }
                                            },
                                            onChangeMasterPassword = { currentPwd, newPwd, cb ->
                                                viewModel.changeMasterPassword(currentPwd, newPwd, cb)
                                            },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.OtaUpdates -> {
                                        OtaUpdatesScreen(
                                            otaUpdateManager = viewModel.otaUpdateManager,
                                            onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
                                            onDownloadUpdate = { viewModel.startUpdateDownload(it) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.About -> {
                                        AboutScreen(
                                            onCopyText = { l, t -> viewModel.copyPublicText(l, t) },
                                            onBack = { viewModel.navigateBack() }
                                        )
                                    }
                                    is SecondaryDestination.None -> {
                                        when (uiState.currentTab) {
                                            PrimaryTab.HOME -> HomeScreen(
                                                uiState = uiState,
                                                onNavigateToTab = { viewModel.navigateToTab(it) },
                                                onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                                onLockVault = { viewModel.lockVault() }
                                            )
                                            PrimaryTab.NOTES -> NotesScreen(
                                                uiState = uiState,
                                                onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                                onSaveNote = { viewModel.saveNote(it) },
                                                onDeleteNote = { id, p -> viewModel.deleteNote(id, p) },
                                                onRestoreNote = { viewModel.restoreNoteFromTrash(it) },
                                                onToggleChecklist = { nid, eid -> viewModel.toggleChecklistItem(nid, eid) },
                                                onSearchChange = { viewModel.setSearchQuery(it) }
                                            )
                                            PrimaryTab.PASSWORDS -> PasswordsScreen(
                                                uiState = uiState,
                                                onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                                onSaveCredential = { viewModel.saveCredential(it) },
                                                onDeleteCredential = { id, p -> viewModel.deleteCredential(id, p) },
                                                onRestoreCredential = { viewModel.restoreCredentialFromTrash(it) },
                                                onCopyPassword = { l, p -> viewModel.copySensitivePassword(l, p) },
                                                onSearchChange = { viewModel.setSearchQuery(it) },
                                                onPickCsvFile = { pickCsvLauncher.launch(arrayOf("*/*")) }
                                            )
                                            PrimaryTab.MEDIA -> MediaScreen(
                                                uiState = uiState,
                                                onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                                onPickMedia = {
                                                    pickVisualMediaLauncher.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                                    )
                                                },
                                                onPickDocument = {
                                                    pickDocumentLauncher.launch(arrayOf("*/*"))
                                                },
                                                onDeleteMedia = { viewModel.deleteMedia(it) },
                                                onDeleteDocument = { viewModel.deleteDocument(it) },
                                                onDeleteLink = { viewModel.deleteLink(it) },
                                                onCreateAlbum = { viewModel.createAlbum(it) },
                                                onDeleteAlbum = { viewModel.deleteAlbum(it) }
                                            )
                                            PrimaryTab.SETTINGS -> SettingsScreen(
                                                uiState = uiState,
                                                onNavigateToSecondary = { viewModel.navigateToSecondary(it) },
                                                onSetTheme = { viewModel.setTheme(it) },
                                                onSetScreenshotProtection = { viewModel.setScreenshotProtection(it) },
                                                onSetAutoLockTimeout = { viewModel.setAutoLockTimeout(it) }
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }

                        if (showFirstLaunchRestoreDialog) {
                            var pwdError by remember { mutableStateOf<String?>(null) }
                            AlertDialog(
                                onDismissRequest = { showFirstLaunchRestoreDialog = false },
                                title = { Text("Restore Vault from Backup") },
                                text = {
                                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                        Text("Enter the backup password used to encrypt the .pvault backup file:", style = MaterialTheme.typography.bodySmall)
                                        OutlinedTextField(
                                            value = firstLaunchRestorePassword,
                                            onValueChange = { firstLaunchRestorePassword = it; pwdError = null },
                                            label = { Text("Backup Password") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (pwdError != null) {
                                            Text(pwdError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (firstLaunchRestorePassword.isEmpty()) {
                                                pwdError = "Backup password cannot be empty"
                                            } else {
                                                pendingRestorePassword = firstLaunchRestorePassword.toCharArray()
                                                showFirstLaunchRestoreDialog = false
                                                openBackupLauncher.launch(arrayOf("*/*"))
                                            }
                                        }
                                    ) {
                                        Text("Select .pvault File")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showFirstLaunchRestoreDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        if (showCloudBackupPasswordDialog) {
                            var cloudPwdError by remember { mutableStateOf<String?>(null) }
                            val strengthReport = remember(cloudBackupPassword) {
                                if (cloudBackupPassword.isNotEmpty()) OfflinePasswordStrengthEstimator.evaluate(cloudBackupPassword) else null
                            }
                            AlertDialog(
                                onDismissRequest = {
                                    cloudBackupPassword = ""
                                    confirmCloudBackupPassword = ""
                                    showCloudBackupPasswordDialog = false
                                },
                                title = { Text("Zero-Knowledge Cloud Backup") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Set a strong password (at least ${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH} characters) to encrypt this cloud snapshot. You will need it to restore from Google Drive.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        OutlinedTextField(
                                            value = cloudBackupPassword,
                                            onValueChange = { cloudBackupPassword = it; cloudPwdError = null },
                                            label = { Text("Cloud Backup Password (${PasswordPolicy.MIN_NEW_PASSWORD_LENGTH}+ chars)") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = confirmCloudBackupPassword,
                                            onValueChange = { confirmCloudBackupPassword = it; cloudPwdError = null },
                                            label = { Text("Confirm Cloud Backup Password") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (strengthReport != null) {
                                            Text(
                                                "Strength: ${strengthReport.band.label} (${strengthReport.estimatedEntropyBits} bits)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (cloudPwdError != null) {
                                            Text(cloudPwdError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            val validation = PasswordPolicy.validateNewPassword(
                                                cloudBackupPassword,
                                                confirmCloudBackupPassword,
                                                "Cloud backup password"
                                            )
                                            if (!validation.isValid) {
                                                cloudPwdError = validation.errorMessage
                                            } else {
                                                val pwdChars = cloudBackupPassword.toCharArray()
                                                cloudBackupPassword = ""
                                                confirmCloudBackupPassword = ""
                                                showCloudBackupPasswordDialog = false
                                                viewModel.uploadCloudBackup(
                                                    backupPasswordChars = pwdChars,
                                                    onConsentRequired = { pi ->
                                                        viewModel.grantFilePickerImmunity()
                                                        pendingConnectedEmailForConsent = uiState.connectedGoogleEmail
                                                        driveConsentLauncher.launch(IntentSenderRequest.Builder(pi).build())
                                                    }
                                                ) { success, err ->
                                                    java.util.Arrays.fill(pwdChars, '\u0000')
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (success) "Cloud backup uploaded successfully"
                                                            else err ?: "Could not upload cloud backup."
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Back Up Now")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            cloudBackupPassword = ""
                                            confirmCloudBackupPassword = ""
                                            showCloudBackupPasswordDialog = false
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        if (showCloudRestorePasswordDialog) {
                            var restorePwdError by remember { mutableStateOf<String?>(null) }
                            AlertDialog(
                                onDismissRequest = {
                                    cloudRestorePassword = ""
                                    showCloudRestorePasswordDialog = false
                                },
                                title = { Text("Restore Cloud Snapshot") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Enter the password that was used to encrypt this cloud snapshot:", style = MaterialTheme.typography.bodySmall)
                                        OutlinedTextField(
                                            value = cloudRestorePassword,
                                            onValueChange = { cloudRestorePassword = it; restorePwdError = null },
                                            label = { Text("Snapshot Password") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (restorePwdError != null) {
                                            Text(restorePwdError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (cloudRestorePassword.isEmpty()) {
                                                restorePwdError = "Password cannot be empty"
                                            } else {
                                                val snapId = pendingDownloadSnapFileId ?: return@Button
                                                val pwd = cloudRestorePassword.toCharArray()
                                                cloudRestorePassword = ""
                                                showCloudRestorePasswordDialog = false
                                                scope.launch { snackbarHostState.showSnackbar("Downloading cloud snapshot...") }
                                                viewModel.downloadCloudSnapshot(
                                                    fileId = snapId,
                                                    onConsentRequired = { pi ->
                                                        viewModel.grantFilePickerImmunity()
                                                        pendingConnectedEmailForConsent = uiState.connectedGoogleEmail
                                                        driveConsentLauncher.launch(IntentSenderRequest.Builder(pi).build())
                                                    }
                                                ) { file, err ->
                                                    if (file != null) {
                                                        lastRestoreCloudFile?.let { runCatching { it.delete() } }
                                                        lastRestoreCloudFile = file
                                                        lastRestoreBackupUri = null
                                                        inspectCloudFileWithPassword(file, pwd)
                                                    } else {
                                                        java.util.Arrays.fill(pwd, '\u0000')
                                                        scope.launch { snackbarHostState.showSnackbar(err ?: "Could not download backup.") }
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Download & Restore")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            cloudRestorePassword = ""
                                            showCloudRestorePasswordDialog = false
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        val friendlyRestoreError = uiState.pendingRestoreError
                        if (friendlyRestoreError != null) {
                            val canRetrySameSource = friendlyRestoreError.canRetryPassword &&
                                (lastRestoreCloudFile != null || lastRestoreBackupUri != null)
                            AlertDialog(
                                onDismissRequest = {
                                    viewModel.dismissRestoreError()
                                },
                                title = { Text(friendlyRestoreError.title) },
                                text = { Text(friendlyRestoreError.message, style = MaterialTheme.typography.bodyMedium) },
                                confirmButton = {
                                    if (canRetrySameSource) {
                                        Button(
                                            onClick = {
                                                viewModel.dismissRestoreError()
                                                retryRestorePassword = ""
                                                showRetryRestorePasswordDialog = true
                                            }
                                        ) {
                                            Text("Try again")
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                viewModel.dismissRestoreError()
                                                lastRestoreCloudFile?.let { runCatching { it.delete() } }
                                                lastRestoreCloudFile = null
                                            }
                                        ) {
                                            Text("OK")
                                        }
                                    }
                                },
                                dismissButton = {
                                    if (canRetrySameSource) {
                                        TextButton(
                                            onClick = {
                                                viewModel.dismissRestoreError()
                                                lastRestoreCloudFile?.let { runCatching { it.delete() } }
                                                lastRestoreCloudFile = null
                                                lastRestoreBackupUri = null
                                            }
                                        ) {
                                            Text(if (friendlyRestoreError.canChooseAnotherFile) "Choose another backup" else "Cancel")
                                        }
                                    }
                                }
                            )
                        }

                        if (showRetryRestorePasswordDialog) {
                            var retryError by remember { mutableStateOf<String?>(null) }
                            AlertDialog(
                                onDismissRequest = {
                                    retryRestorePassword = ""
                                    showRetryRestorePasswordDialog = false
                                },
                                title = { Text("Enter Backup Password") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Re-enter the password for the selected backup file:",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        OutlinedTextField(
                                            value = retryRestorePassword,
                                            onValueChange = { retryRestorePassword = it; retryError = null },
                                            label = { Text("Backup Password") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (retryError != null) {
                                            Text(retryError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (retryRestorePassword.isEmpty()) {
                                                retryError = "Password cannot be empty."
                                            } else {
                                                val pwdChars = retryRestorePassword.toCharArray()
                                                retryRestorePassword = ""
                                                showRetryRestorePasswordDialog = false
                                                val cloudFile = lastRestoreCloudFile
                                                val localUri = lastRestoreBackupUri
                                                when {
                                                    cloudFile != null && cloudFile.exists() -> inspectCloudFileWithPassword(cloudFile, pwdChars)
                                                    localUri != null -> inspectLocalUriWithPassword(localUri, pwdChars)
                                                    else -> {
                                                        java.util.Arrays.fill(pwdChars, '\u0000')
                                                        scope.launch { snackbarHostState.showSnackbar("Choose the backup file again.") }
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Unlock Backup")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            retryRestorePassword = ""
                                            showRetryRestorePasswordDialog = false
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        val incomingShared = uiState.pendingSharedText
                        if (uiState.isVaultInitialized && uiState.isUnlocked && incomingShared != null) {
                            val trimmedShared = incomingShared.trim()
                            val looksLikeUrl = remember(trimmedShared) {
                                trimmedShared.startsWith("https://", ignoreCase = true) ||
                                    trimmedShared.startsWith("http://", ignoreCase = true)
                            }
                            var saveAsLink by remember(incomingShared) { mutableStateOf(looksLikeUrl) }
                            var shareTitle by remember(incomingShared) {
                                mutableStateOf(
                                    if (looksLikeUrl) {
                                        runCatching { Uri.parse(trimmedShared).host }.getOrNull().orEmpty().ifBlank { "Shared Link" }
                                    } else {
                                        trimmedShared.lineSequence().firstOrNull { it.isNotBlank() }?.take(50) ?: "Shared Note"
                                    }
                                )
                            }
                            var shareContent by remember(incomingShared) { mutableStateOf(trimmedShared) }
                            var shareError by remember { mutableStateOf<String?>(null) }

                            AlertDialog(
                                onDismissRequest = { viewModel.clearPendingSharedText() },
                                title = { Text("Save to Private Vault") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = !saveAsLink,
                                                onClick = { saveAsLink = false; shareError = null },
                                                label = { Text("Note") }
                                            )
                                            FilterChip(
                                                selected = saveAsLink,
                                                onClick = { saveAsLink = true; shareError = null },
                                                label = { Text("Secure Link") }
                                            )
                                        }
                                        OutlinedTextField(
                                            value = shareTitle,
                                            onValueChange = { shareTitle = it; shareError = null },
                                            label = { Text("Title") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = shareContent,
                                            onValueChange = { shareContent = it; shareError = null },
                                            label = { Text(if (saveAsLink) "URL" else "Note Content") },
                                            maxLines = 5,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (shareError != null) {
                                            Text(shareError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (saveAsLink) {
                                                viewModel.saveSharedTextAsLink(shareTitle, shareContent) { ok, err ->
                                                    if (!ok) shareError = err
                                                }
                                            } else {
                                                viewModel.saveSharedTextAsNote(shareTitle, shareContent) { ok, err ->
                                                    if (!ok) shareError = err
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Save")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { viewModel.clearPendingSharedText() }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingSendIntent(intent)
    }

    private fun handleIncomingSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (sharedText.isNotBlank()) {
                viewModel.setPendingSharedText(sharedText)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOffReceiver) }
    }
}
