package com.tpeditz.privatevault.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordPolicy
import com.tpeditz.privatevault.v2.sync.GoogleDriveSyncManager
import com.tpeditz.privatevault.v2.ui.VaultUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CloudBackupScreen(
    uiState: VaultUiState,
    isDriveConfigured: Boolean,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: () -> Unit,
    onUploadCloudBackup: (CharArray?) -> Unit,
    onConfigureAutoBackup: (enabled: Boolean, recoveryPassword: CharArray?) -> Unit,
    onSetCloudFrequency: (CloudBackupFrequency) -> Unit,
    onSetCloudWifiOnly: (Boolean) -> Unit,
    onQuerySnapshots: (onResult: (List<GoogleDriveSyncManager.CloudBackupMetadata>, String?) -> Unit) -> Unit,
    onDownloadSnapshot: (String) -> Unit,
    onDeleteSnapshot: (String) -> Unit,
    onBack: () -> Unit
) {
    val snapshots = remember { mutableStateListOf<GoogleDriveSyncManager.CloudBackupMetadata>() }
    var isLoadingSnapshots by remember { mutableStateOf(false) }
    var snapshotsError by remember { mutableStateOf<String?>(null) }

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var pendingDeleteSnapshot by remember { mutableStateOf<GoogleDriveSyncManager.CloudBackupMetadata?>(null) }

    var showAutoSetupDialog by remember { mutableStateOf(false) }
    var autoPwd by remember { mutableStateOf("") }
    var autoPwdConfirm by remember { mutableStateOf("") }
    var autoSetupError by remember { mutableStateOf<String?>(null) }

    val connectedEmail = uiState.connectedGoogleEmail

    fun refreshList() {
        if (connectedEmail.isBlank()) {
            snapshots.clear()
            snapshotsError = null
            return
        }
        isLoadingSnapshots = true
        snapshotsError = null
        onQuerySnapshots { list, err ->
            snapshots.clear()
            snapshots.addAll(list)
            snapshotsError = err
            isLoadingSnapshots = false
        }
    }

    LaunchedEffect(connectedEmail, uiState.lastCloudBackupEpochMs) {
        refreshList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive backup", fontWeight = FontWeight.Bold) },
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
            if (uiState.onlineMaintenanceActive) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Online services are temporarily in maintenance mode. Your encrypted local vault and local backups continue to work normally.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (uiState.isLoading && uiState.backupStatusMessage != null) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(uiState.backupStatusMessage, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // Account & Manual Backup Card
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Google Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        if (!isDriveConfigured) {
                            Text(
                                "Google Drive backup is not enabled in this build. Local encrypted backups remain fully available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (connectedEmail.isBlank()) {
                            Text(
                                "Not connected. Connect your Google account to save encrypted backups privately to your Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onConnectGoogle, modifier = Modifier.fillMaxWidth()) {
                                Text("Connect Google account")
                            }
                        } else {
                            Text("Connected as $connectedEmail", fontWeight = FontWeight.SemiBold)
                            val lastBackupLabel = if (uiState.lastCloudBackupEpochMs > 0L) {
                                SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(uiState.lastCloudBackupEpochMs))
                            } else {
                                "No backup uploaded yet"
                            }
                            Text(
                                "Last successful backup: $lastBackupLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Your backup is encrypted on this device with your backup password before upload. Google cannot read your vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onUploadCloudBackup(null) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isLoading
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Back up now")
                                }
                                OutlinedButton(
                                    onClick = { showDisconnectDialog = true },
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isLoading
                                ) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                }
            }

            // Automatic Backup Scheduling Card
            if (isDriveConfigured && connectedEmail.isNotBlank()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = uiState.autoCloudBackupEnabled,
                                        role = Role.Switch,
                                        onValueChange = { wantEnable ->
                                            if (wantEnable) {
                                                autoPwd = ""
                                                autoPwdConfirm = ""
                                                autoSetupError = null
                                                showAutoSetupDialog = true
                                            } else {
                                                onConfigureAutoBackup(false, null)
                                            }
                                        }
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Automatic backup", fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "Creates encrypted snapshots while unlocked and uploads automatically on schedule.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Switch(
                                    checked = uiState.autoCloudBackupEnabled,
                                    onCheckedChange = null
                                )
                            }

                            if (!uiState.lastCloudBackupStatusMessage.isNullOrBlank()) {
                                Text(
                                    text = "Status: ${uiState.lastCloudBackupStatusMessage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            if (uiState.autoCloudBackupEnabled) {
                                HorizontalDivider()

                                Column {
                                    Text("Schedule frequency", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        CloudBackupFrequency.entries.forEach { freq ->
                                            val label = when (freq) {
                                                CloudBackupFrequency.DAILY -> "Daily"
                                                CloudBackupFrequency.WEEKLY -> "Weekly"
                                                CloudBackupFrequency.MONTHLY -> "Monthly"
                                            }
                                            FilterChip(
                                                selected = uiState.cloudBackupFrequency == freq,
                                                onClick = { onSetCloudFrequency(freq) },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    val approxDays = when (uiState.cloudBackupFrequency) {
                                        CloudBackupFrequency.DAILY -> "roughly every 24 hours"
                                        CloudBackupFrequency.WEEKLY -> "roughly once a week"
                                        CloudBackupFrequency.MONTHLY -> "roughly once a month"
                                    }
                                    val nextText = if (uiState.lastCloudBackupEpochMs > 0L) {
                                        val nextMs = uiState.lastCloudBackupEpochMs + when (uiState.cloudBackupFrequency) {
                                            CloudBackupFrequency.DAILY -> 24L * 60 * 60 * 1000
                                            CloudBackupFrequency.WEEKLY -> 7L * 24 * 60 * 60 * 1000
                                            CloudBackupFrequency.MONTHLY -> 30L * 24 * 60 * 60 * 1000
                                        }
                                        val nextDate = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(nextMs))
                                        "Next automatic backup: around $nextDate ($approxDays)"
                                    } else {
                                        "Runs automatically $approxDays when conditions are met"
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(nextText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }

                                if (uiState.isCloudRecoveryPasswordLegacyShort) {
                                    Text(
                                        "Your current automatic backup password is shorter than 12 characters. Existing schedules still work, or you can upgrade to a stronger 12+ character password below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        autoPwd = ""
                                        autoPwdConfirm = ""
                                        autoSetupError = null
                                        showAutoSetupDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Change automatic backup password")
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = uiState.cloudBackupWifiOnly,
                                            role = Role.Switch,
                                            onValueChange = { onSetCloudWifiOnly(it) }
                                        )
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Upload on Wi-Fi only", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (uiState.cloudBackupWifiOnly) "Waiting for Wi-Fi to save mobile data" else "Uploads on Wi-Fi or mobile data",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Switch(
                                        checked = uiState.cloudBackupWifiOnly,
                                        onCheckedChange = null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Available Cloud Snapshots
            if (connectedEmail.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Saved cloud backups (Latest 3)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = { refreshList() },
                            enabled = !isLoadingSnapshots
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh backups")
                        }
                    }
                }

                if (isLoadingSnapshots) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (snapshotsError != null) {
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Could not load backups", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                                Text(snapshotsError!!, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { refreshList() }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                } else if (snapshots.isEmpty()) {
                    item {
                        Text("No cloud backups found for this account yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    items(snapshots, key = { it.fileId }) { snap ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snap.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    val dateStr = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(snap.modifiedTimeEpochMs))
                                    val sizeStr = formatFileSize(snap.sizeBytes)
                                    Text("$dateStr • $sizeStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(onClick = { onDownloadSnapshot(snap.fileId) }) {
                                    Icon(Icons.Default.Download, contentDescription = "Restore backup", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { pendingDeleteSnapshot = snap }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete backup", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAutoSetupDialog) {
        AlertDialog(
            onDismissRequest = { showAutoSetupDialog = false },
            title = { Text("Set automatic backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Protect and remember this backup password. You will need it to restore your vault on a new device — signing in to Google alone cannot decrypt your backups.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = autoPwd,
                        onValueChange = { autoPwd = it; autoSetupError = null },
                        label = { Text("Backup Password") },
                        supportingText = {
                            if (autoPwd.isNotBlank()) {
                                val report = OfflinePasswordStrengthEstimator.evaluate(autoPwd)
                                Text("Strength: ${report.band.label} (${autoPwd.length}/12+ chars)")
                            } else {
                                Text("Use 12+ characters (14+ or passphrase with spaces recommended)")
                            }
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = autoPwdConfirm,
                        onValueChange = { autoPwdConfirm = it; autoSetupError = null },
                        label = { Text("Confirm Backup Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (autoSetupError != null) {
                        Text(autoSetupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val validation = PasswordPolicy.validateNewPassword(autoPwd, autoPwdConfirm, "Backup password")
                        if (!validation.isValid) {
                            autoSetupError = validation.errorMessage
                        } else {
                            val chars = autoPwd.toCharArray()
                            autoPwd = ""
                            autoPwdConfirm = ""
                            showAutoSetupDialog = false
                            onConfigureAutoBackup(true, chars)
                        }
                    }
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoSetupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingDeleteSnapshot != null) {
        val snap = pendingDeleteSnapshot!!
        AlertDialog(
            onDismissRequest = { pendingDeleteSnapshot = null },
            title = { Text("Delete cloud backup?") },
            text = { Text("Delete \"${snap.fileName}\" from your Google Drive? Your local vault on this device will not be changed.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteSnapshot = null
                        onDeleteSnapshot(snap.fileId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSnapshot = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect Google account?") },
            text = { Text("Disconnecting stops automatic backups and removes Google account access from Private Vault. Your local vault and existing backups on Google Drive will remain untouched.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectDialog = false
                        onDisconnectGoogle()
                    }
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
