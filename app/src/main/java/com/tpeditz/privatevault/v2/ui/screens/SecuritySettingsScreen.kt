package com.tpeditz.privatevault.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordPolicy
import com.tpeditz.privatevault.v2.ui.VaultUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SecuritySettingsScreen(
    uiState: VaultUiState,
    onSetScreenshotProtection: (Boolean) -> Unit,
    onSetLockOnScreenOff: (Boolean) -> Unit,
    onSetAutoLockTimeout: (Int) -> Unit,
    onSetClipboardTtl: (Int) -> Unit,
    onEnableBiometrics: () -> Unit,
    onDisableBiometrics: () -> Unit,
    onProvisionDecoy: (CharArray, CharArray) -> Unit,
    onDisableDecoy: (CharArray) -> Unit,
    onChangeMasterPassword: (CharArray, CharArray, (Boolean, String?) -> Unit) -> Unit = { _, _, _ -> },
    onBack: () -> Unit
) {
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var currentMasterPwd by remember { mutableStateOf("") }
    var newMasterPwd by remember { mutableStateOf("") }
    var confirmNewMasterPwd by remember { mutableStateOf("") }
    var changePwdError by remember { mutableStateOf<String?>(null) }

    var showDisableDecoyDialog by remember { mutableStateOf(false) }
    var primaryPwdForDisableDecoy by remember { mutableStateOf("") }
    var disableDecoyError by remember { mutableStateOf<String?>(null) }

    var showDecoyDialog by remember { mutableStateOf(false) }
    var primaryPwdForDecoy by remember { mutableStateOf("") }
    var decoyPwd by remember { mutableStateOf("") }
    var confirmDecoyPwd by remember { mutableStateOf("") }
    var decoyError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Locking & Screen Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = uiState.lockOnScreenOff,
                                    role = Role.Switch,
                                    onValueChange = { onSetLockOnScreenOff(it) }
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Lock when screen turns off", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Immediately locks the vault when your device display turns off.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = uiState.lockOnScreenOff,
                                onCheckedChange = null
                            )
                        }

                        HorizontalDivider()

                        Column {
                            Text("Background Auto-Lock Timeout", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Locks the vault after leaving the app for the selected duration.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(0 to "Immediate", 30 to "30s", 60 to "1m", 300 to "5m").forEach { (sec, label) ->
                                    FilterChip(
                                        selected = uiState.autoLockTimeoutSeconds == sec,
                                        onClick = { onSetAutoLockTimeout(sec) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = uiState.screenshotProtectionEnabled,
                                    role = Role.Switch,
                                    onValueChange = { onSetScreenshotProtection(it) }
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Screen capture protection", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (uiState.screenshotProtectionEnabled) {
                                        "Blocks screenshots, screen recordings, and hides previews in recent apps."
                                    } else {
                                        "Capture is permitted on unlocked screens; master-password screens remain protected."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = uiState.screenshotProtectionEnabled,
                                onCheckedChange = null
                            )
                        }

                        HorizontalDivider()

                        val bioEnabled = !uiState.isDecoyPolicyConfigured
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = uiState.isBiometricsConfigured,
                                    enabled = bioEnabled,
                                    role = Role.Switch,
                                    onValueChange = { wantEnable ->
                                        if (wantEnable) onEnableBiometrics() else onDisableBiometrics()
                                    }
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Biometric Unlock", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (!bioEnabled) {
                                        "Unavailable while a Secondary / Decoy vault is active."
                                    } else if (uiState.isBiometricsConfigured) {
                                        "Fingerprint or device biometric quick-unlock is enabled."
                                    } else {
                                        "Use fingerprint or device biometrics to unlock your vault."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = uiState.isBiometricsConfigured,
                                onCheckedChange = null,
                                enabled = bioEnabled
                            )
                        }
                    }
                }
            }

            // Clipboard Protection
            item {
                Text("Clipboard Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Clipboard Auto-Clear Timeout", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Clears vault passwords from your clipboard after the selected countdown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(15 to "15s", 30 to "30s", 60 to "60s", 0 to "Manual").forEach { (sec, label) ->
                                FilterChip(
                                    selected = uiState.clipboardTtlSeconds == sec,
                                    onClick = { onSetClipboardTtl(sec) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Master Password Section
            item {
                Text("Master Password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Change the master password that protects your primary vault. Zero-knowledge envelope encryption ensures your data remains intact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Current Status: Secured with Argon2id + AES-256-GCM envelope")
                        Button(
                            onClick = {
                                currentMasterPwd = ""
                                newMasterPwd = ""
                                confirmNewMasterPwd = ""
                                changePwdError = null
                                showChangePasswordDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Change Master Password")
                        }
                    }
                }
            }

            // Decoy Vault Section
            item {
                Text("Secondary / Decoy Vault", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Optional personal privacy feature providing a separate, isolated secondary vault accessed with a different master password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uiState.isDecoyPolicyConfigured) {
                            Text("Decoy Vault is currently active.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text("Unlocking with the secondary password opens an isolated data collection with zero access to your primary records.", style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    primaryPwdForDisableDecoy = ""
                                    disableDecoyError = null
                                    showDisableDecoyDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Disable Decoy Vault")
                            }
                        } else {
                            Text("Decoy Vault is currently disabled.")
                            Button(
                                onClick = { showDecoyDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Configure Decoy Vault")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDecoyDialog) {
        AlertDialog(
            onDismissRequest = { showDecoyDialog = false },
            title = { Text("Setup Decoy Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter your current primary vault password, then choose a separate password for the decoy vault.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = primaryPwdForDecoy,
                        onValueChange = { primaryPwdForDecoy = it },
                        label = { Text("Primary Vault Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = decoyPwd,
                        onValueChange = { decoyPwd = it },
                        label = { Text("Decoy Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmDecoyPwd,
                        onValueChange = { confirmDecoyPwd = it },
                        label = { Text("Confirm Decoy Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (decoyError != null) {
                        Text(decoyError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                    Button(
                    onClick = {
                        if (primaryPwdForDecoy.isEmpty()) {
                            decoyError = "Enter your current primary vault password."
                        } else {
                            val decoyValidation = PasswordPolicy.validateNewPassword(decoyPwd, confirmDecoyPwd, "Decoy password")
                            if (!decoyValidation.isValid) {
                                decoyError = decoyValidation.errorMessage
                            } else if (primaryPwdForDecoy == decoyPwd) {
                                decoyError = "Decoy password must differ from primary password"
                            } else {
                                onProvisionDecoy(primaryPwdForDecoy.toCharArray(), decoyPwd.toCharArray())
                                showDecoyDialog = false
                            }
                        }
                    }
                ) {
                    Text("Create Decoy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDecoyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDisableDecoyDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDecoyDialog = false },
            title = { Text("Disable Decoy Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter your primary vault password to confirm disabling the decoy vault:")
                    OutlinedTextField(
                        value = primaryPwdForDisableDecoy,
                        onValueChange = { primaryPwdForDisableDecoy = it },
                        label = { Text("Primary Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (disableDecoyError != null) {
                        Text(disableDecoyError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (primaryPwdForDisableDecoy.isEmpty()) {
                            disableDecoyError = "Password cannot be empty"
                        } else {
                            onDisableDecoy(primaryPwdForDisableDecoy.toCharArray())
                            showDisableDecoyDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDecoyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showChangePasswordDialog = false },
            title = { Text("Change Master Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentMasterPwd,
                        onValueChange = { currentMasterPwd = it; changePwdError = null },
                        label = { Text("Current Master Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newMasterPwd,
                        onValueChange = { newMasterPwd = it; changePwdError = null },
                        label = { Text("New Master Password") },
                        supportingText = {
                            if (newMasterPwd.isNotBlank()) {
                                val report = OfflinePasswordStrengthEstimator.evaluate(newMasterPwd)
                                Text("Strength: ${report.band.label} (${newMasterPwd.length}/12+ chars)")
                            } else {
                                Text("12+ characters recommended (spaces allowed)")
                            }
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmNewMasterPwd,
                        onValueChange = { confirmNewMasterPwd = it; changePwdError = null },
                        label = { Text("Confirm New Master Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (changePwdError != null) {
                        Text(changePwdError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                    Button(
                    onClick = {
                        if (currentMasterPwd.isEmpty()) {
                            changePwdError = "Enter your current password."
                        } else {
                            val validation = PasswordPolicy.validateNewPassword(newMasterPwd, confirmNewMasterPwd, "New master password")
                            if (!validation.isValid) {
                                changePwdError = validation.errorMessage
                            } else if (currentMasterPwd == newMasterPwd) {
                                changePwdError = "New password must be different."
                            } else {
                                onChangeMasterPassword(currentMasterPwd.toCharArray(), newMasterPwd.toCharArray()) { success, err ->
                                    if (success) {
                                        showChangePasswordDialog = false
                                    } else {
                                        changePwdError = err ?: "Incorrect current password."
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text("Update Password")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
