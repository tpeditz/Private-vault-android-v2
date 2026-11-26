package com.tpeditz.privatevault.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.data.BackupExportScope
import com.tpeditz.privatevault.v2.data.BackupPayload
import com.tpeditz.privatevault.v2.data.RestoreMode
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordPolicy
import com.tpeditz.privatevault.v2.ui.VaultUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    uiState: VaultUiState,
    onTriggerCreateBackupDocument: (BackupExportScope, CharArray) -> Unit,
    onTriggerOpenBackupDocument: (CharArray) -> Unit,
    onConfirmRestore: (RestoreMode) -> Unit,
    onCancelOperation: () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedScope by remember { mutableStateOf(BackupExportScope.ALL_DATA) }
    var backupPassword by remember { mutableStateOf("") }
    var confirmBackupPassword by remember { mutableStateOf("") }
    var isBackupPwdVisible by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    var restorePassword by remember { mutableStateOf("") }
    var isRestorePwdVisible by remember { mutableStateOf(false) }

    var showReplaceConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Backup & Restore", fontWeight = FontWeight.Bold) },
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
            // Live Progress & Cancellation Card
            if (uiState.isLoading || uiState.backupStatusMessage != null) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = uiState.backupStatusMessage ?: "Processing...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val progress = uiState.backupProgressRatio
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(onClick = onCancelOperation) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // Export Section
            item {
                Text("Create Portable Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Backs up the currently unlocked vault into an encrypted .pvault file (up to 2 GB). You can restore this file on another device with its password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Select Export Scope", fontWeight = FontWeight.SemiBold)
                        BackupExportScope.values().forEach { scope ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedScope = scope }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = selectedScope == scope,
                                    onClick = { selectedScope = scope },
                                    label = { Text(scope.label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = {
                                backupPassword = it
                                exportError = null
                            },
                            label = { Text("Set Backup Password") },
                            supportingText = {
                                if (backupPassword.isNotBlank()) {
                                    val report = OfflinePasswordStrengthEstimator.evaluate(backupPassword)
                                    Text("Strength: ${report.band.label} (${backupPassword.length}/12+ chars)")
                                } else {
                                    Text("Use 12+ characters (14+ or a passphrase with spaces recommended)")
                                }
                            },
                            singleLine = true,
                            visualTransformation = if (isBackupPwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isBackupPwdVisible = !isBackupPwdVisible }) {
                                    Icon(if (isBackupPwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle password visibility")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = confirmBackupPassword,
                            onValueChange = {
                                confirmBackupPassword = it
                                exportError = null
                            },
                            label = { Text("Confirm Backup Password") },
                            singleLine = true,
                            visualTransformation = if (isBackupPwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (exportError != null) {
                            Text(exportError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                val validation = PasswordPolicy.validateNewPassword(backupPassword, confirmBackupPassword, "Backup password")
                                if (!validation.isValid) {
                                    exportError = validation.errorMessage
                                } else {
                                    onTriggerCreateBackupDocument(selectedScope, backupPassword.toCharArray())
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Back Up Now")
                        }
                    }
                }
            }

            // Restore Section
            item {
                Text("Restore Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Restore records and media from a .pvault file (supports Private Vault V2/V3 and Legacy V1 backups).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = restorePassword,
                            onValueChange = { restorePassword = it },
                            label = { Text("Backup Password") },
                            singleLine = true,
                            visualTransformation = if (isRestorePwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isRestorePwdVisible = !isRestorePwdVisible }) {
                                    Icon(if (isRestorePwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (restorePassword.isNotEmpty()) {
                                    onTriggerOpenBackupDocument(restorePassword.toCharArray())
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select .pvault File to Restore")
                        }
                    }
                }
            }

            // Restore Preview if payload loaded
            val payload = uiState.pendingRestorePayload
            if (payload != null) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Backup Validated Successfully", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Backup Scope: ${payload.scopeDisplayLabel}", fontWeight = FontWeight.SemiBold)
                            Text("Source Version: ${payload.appVersionName} (${payload.applicationId})")
                            Text("Exported: ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(payload.exportedAtEpochMs))}")

                            when {
                                payload.isPartialCategoryBackup -> {
                                    Text(
                                        text = "Partial category backup. Restoring merges these items into your vault while preserving unrelated categories and folders. Full replacement is disabled.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                !payload.isExportScopeKnown -> {
                                    Text(
                                        text = "Backup scope unknown (older format or missing scope marker). Merge is recommended so existing records in other categories are kept safe.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "Merge adds/updates newer items non-destructively. Replace Active overwrites your active vault with this verified full backup.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Contents:")
                            Text("• ${payload.notesCount} Notes")
                            Text("• ${payload.credentialsCount} Credentials")
                            Text("• ${payload.mediaCount} Media items")
                            Text("• ${payload.documentsCount} Documents")
                            Text("• ${payload.linksCount} Secure Links")
                            Text("• ${payload.authenticatorsCount} Authenticator accounts")

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onConfirmRestore(RestoreMode.MERGE_KEEP_NEWEST) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (!payload.isExportScopeKnown) "Merge (Recommended)" else "Merge (Keep Newest)")
                                }
                                OutlinedButton(
                                    onClick = { showReplaceConfirmDialog = true },
                                    enabled = !payload.isPartialCategoryBackup,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Replace Active")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogPayload = uiState.pendingRestorePayload
    if (showReplaceConfirmDialog && dialogPayload != null && !dialogPayload.isPartialCategoryBackup) {
        val replaceWarning = if (!dialogPayload.isExportScopeKnown) {
            "Because this backup's scope is unknown, replacing your active vault will permanently erase ALL current notes, passwords, media, documents, and folders — even items missing from this backup. This cannot be undone."
        } else {
            "This will permanently overwrite existing records in the currently active vault with the records from the backup file. This cannot be undone."
        }
        AlertDialog(
            onDismissRequest = { showReplaceConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Replace Active Vault?") },
            text = { Text(replaceWarning) },
            confirmButton = {
                Button(
                    onClick = {
                        showReplaceConfirmDialog = false
                        onConfirmRestore(RestoreMode.REPLACE_ACTIVE_VAULT)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
