package com.tpeditz.privatevault.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.ota.isPendingInstallation
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaUpdatesScreen(
    otaUpdateManager: OtaUpdateManager,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (com.tpeditz.privatevault.v2.ota.UpdateManifest) -> Unit = {},
    automaticCheckEnabled: Boolean = true,
    onSetAutomaticCheck: (Boolean) -> Unit = {},
    betaChannelOptIn: Boolean = false,
    betaEnrollmentAllowed: Boolean = true,
    onlineMaintenanceActive: Boolean = false,
    otaCheckIntervalHours: Int = 12,
    onSetBetaChannelOptIn: (Boolean) -> Unit = {},
    onInstallNow: (File) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onOpenUnknownSources: () -> Unit = {},
    onRefreshPermission: () -> Unit = {},
    onBack: () -> Unit
) {
    val otaState by otaUpdateManager.otaState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates", fontWeight = FontWeight.Bold) },
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
            // Version Info Card
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Current Version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Updates are verified with Private Vault's permanent signing certificate before installation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automatically check for updates", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Background checks run every $otaCheckIntervalHours hours when enabled.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = automaticCheckEnabled, onCheckedChange = onSetAutomaticCheck)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Beta release channel (opt-in)", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (!betaEnrollmentAllowed) {
                                        "Beta enrollment is currently disabled by release policy."
                                    } else if (betaChannelOptIn) {
                                        "Receiving pre-release Beta updates alongside Stable."
                                    } else {
                                        "Receiving Stable releases only."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = betaChannelOptIn && betaEnrollmentAllowed,
                                enabled = betaEnrollmentAllowed,
                                onCheckedChange = onSetBetaChannelOptIn
                            )
                        }

                        if (onlineMaintenanceActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Online update services are currently paused for maintenance. Local vault data remains unaffected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onCheckForUpdates,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = otaState !is OtaState.Checking && otaState !is OtaState.Downloading &&
                                otaState !is OtaState.Verifying && !otaState.isPendingInstallation
                        ) {
                            if (otaState is OtaState.Checking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking...")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Check for Updates")
                            }
                        }
                    }
                }
            }

            // State Display
            item {
                when (val state = otaState) {
                    is OtaState.UpToDate -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("You're up to date", fontWeight = FontWeight.Bold)
                                    Text("Private Vault ${state.currentVersionName} is the latest release.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }

                    is OtaState.UpdateAvailable -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Update Available: v${state.manifest.versionName}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "${state.channel.label} Channel • ${state.priority.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (state.manifest.releaseNotes.isNotBlank()) {
                                    Text("Release Notes:\n${state.manifest.releaseNotes}", style = MaterialTheme.typography.bodySmall)
                                }
                                val sizeStr = formatFileSize(state.manifest.sizeBytes)
                                Text("Download Size: $sizeStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { onDownloadUpdate(state.manifest) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Download & Install")
                                    }
                                    OutlinedButton(onClick = onBack) {
                                        Text("Later")
                                    }
                                }
                            }
                        }
                    }

                    is OtaState.Downloading -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Downloading Update...", fontWeight = FontWeight.Bold)
                                val progress = if (state.totalBytes > 0) state.bytesDownloaded.toFloat() / state.totalBytes.toFloat() else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Text("${formatFileSize(state.bytesDownloaded)} / ${formatFileSize(state.totalBytes)}", style = MaterialTheme.typography.labelSmall)
                                OutlinedButton(
                                    onClick = onCancelDownload,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }

                    is OtaState.Verifying -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Verifying update checksum and signature...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    is OtaState.ReadyToInstall -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Update Verified & Ready", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Private Vault v${state.manifest.versionName} passed checksum and signature verification.", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { onInstallNow(state.verifiedApkFile) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Install Now")
                                    }
                                    OutlinedButton(onClick = onCancelDownload) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }

                    is OtaState.NeedsUnknownSourcesPermission -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Permission Needed to Install", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Your update is already downloaded. Allow installs from this source in Android Settings, then press Back. Private Vault will keep the update and check permission automatically.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onOpenUnknownSources,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Open Settings")
                                    }
                                    OutlinedButton(onClick = onCancelDownload) {
                                        Text("Cancel")
                                    }
                                }
                                OutlinedButton(onClick = onRefreshPermission, modifier = Modifier.fillMaxWidth()) {
                                    Text("I've allowed permission — check again")
                                }
                            }
                        }
                    }

                    is OtaState.PolicyDeferred -> {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(state.title, fontWeight = FontWeight.Bold)
                                }
                                Text(state.detail, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = onCheckForUpdates, modifier = Modifier.fillMaxWidth()) {
                                    Text("Re-check Policy")
                                }
                            }
                        }
                    }

                    is OtaState.Error -> {
                        val isNeutralNoRelease = state.title == "No Release Published Yet"
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    state.title,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isNeutralNoRelease) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                )
                                Text(state.detail, style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val retryManifest = state.recoverableManifest
                                    if (retryManifest != null) {
                                        Button(
                                            onClick = { onDownloadUpdate(retryManifest) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Try Again")
                                        }
                                        OutlinedButton(onClick = onCancelDownload) {
                                            Text("Cancel")
                                        }
                                    } else {
                                        OutlinedButton(onClick = onCheckForUpdates, modifier = Modifier.weight(1f)) {
                                            Text("Check Again")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
