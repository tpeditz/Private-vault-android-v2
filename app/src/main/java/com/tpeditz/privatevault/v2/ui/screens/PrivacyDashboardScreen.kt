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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.ui.PrimaryTab
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(
    uiState: VaultUiState,
    onNavigateToTab: (PrimaryTab) -> Unit,
    onNavigateToSecondary: (SecondaryDestination) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Dashboard", fontWeight = FontWeight.Bold) },
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
            // Password Health Score Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Password Security Score",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Approximate health based on offline evaluation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${uiState.privacyMetrics.averagePasswordScore}/100",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { (uiState.privacyMetrics.averagePasswordScore.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (uiState.privacyMetrics.weakPasswordsCount > 0) {
                                Button(
                                    onClick = { onNavigateToSecondary(SecondaryDestination.Tools) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Fix ${uiState.privacyMetrics.weakPasswordsCount} Weak")
                                }
                            }
                            if (uiState.privacyMetrics.reusedPasswordsCount > 0) {
                                Button(
                                    onClick = { onNavigateToSecondary(SecondaryDestination.Tools) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Fix ${uiState.privacyMetrics.reusedPasswordsCount} Reused")
                                }
                            }
                        }
                    }
                }
            }

            // Vault Storage Breakdown
            item {
                Text("Stored Private Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBox(title = "Notes", count = uiState.notes.size, modifier = Modifier.weight(1f))
                    MetricBox(title = "Passwords", count = uiState.credentials.size, modifier = Modifier.weight(1f))
                    MetricBox(title = "Media", count = uiState.media.size, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBox(title = "Documents", count = uiState.documents.size, modifier = Modifier.weight(1f))
                    MetricBox(title = "Secure Links", count = uiState.secureLinks.size, modifier = Modifier.weight(1f))
                    MetricBox(title = "Authenticator", count = uiState.authenticators.size, modifier = Modifier.weight(1f))
                }
            }

            // Backup Status
            item {
                Text("Backup Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val localDateStr = if (uiState.privacyMetrics.lastLocalBackupEpochMs > 0) {
                            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(uiState.privacyMetrics.lastLocalBackupEpochMs))
                        } else {
                            "Never created"
                        }
                        val cloudDateStr = if (uiState.privacyMetrics.lastCloudBackupEpochMs > 0) {
                            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(uiState.privacyMetrics.lastCloudBackupEpochMs))
                        } else {
                            "Never uploaded"
                        }

                        StatusRow(
                            title = "Local Encrypted Backup",
                            subtitle = localDateStr,
                            isGood = uiState.privacyMetrics.lastLocalBackupEpochMs > 0
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow(
                            title = "Google Drive Cloud Backup",
                            subtitle = cloudDateStr,
                            isGood = uiState.privacyMetrics.lastCloudBackupEpochMs > 0
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { onNavigateToSecondary(SecondaryDestination.BackupAndRestore) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Manage Backups")
                        }
                    }
                }
            }

            // Security Features Status
            item {
                Text("System Protections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                val timeoutSummary = when (uiState.autoLockTimeoutSeconds) {
                    0 -> "Immediate when leaving app"
                    30 -> "30 seconds after leaving app"
                    60 -> "1 minute after leaving app"
                    300 -> "5 minutes after leaving app"
                    else -> "${uiState.autoLockTimeoutSeconds}s after leaving app"
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        StatusRow(
                            title = "Screen Capture Protection",
                            subtitle = if (uiState.screenshotProtectionEnabled) {
                                "Enabled — blocks screenshots and recent-apps previews"
                            } else {
                                "Disabled on unlocked screens"
                            },
                            isGood = uiState.screenshotProtectionEnabled,
                            onClick = { onNavigateToSecondary(SecondaryDestination.SecuritySettings) }
                        )
                        StatusRow(
                            title = "Lock When Screen Turns Off",
                            subtitle = if (uiState.lockOnScreenOff) "Locks immediately on screen off" else "Disabled",
                            isGood = uiState.lockOnScreenOff,
                            onClick = { onNavigateToSecondary(SecondaryDestination.SecuritySettings) }
                        )
                        StatusRow(
                            title = "Background Auto-Lock",
                            subtitle = timeoutSummary,
                            isGood = true,
                            onClick = { onNavigateToSecondary(SecondaryDestination.SecuritySettings) }
                        )
                        StatusRow(
                            title = "Biometric Unlock",
                            subtitle = if (uiState.isBiometricsConfigured) "Hardware Keystore protected" else "Disabled",
                            isGood = uiState.isBiometricsConfigured,
                            onClick = { onNavigateToSecondary(SecondaryDestination.SecuritySettings) }
                        )
                        StatusRow(
                            title = "Automatic Cloud Backup",
                            subtitle = if (uiState.privacyMetrics.isAutoBackupEnabled) "Scheduled opt-in backup active" else "Off",
                            isGood = uiState.privacyMetrics.isAutoBackupEnabled,
                            onClick = { onNavigateToSecondary(SecondaryDestination.CloudBackup) }
                        )
                        StatusRow(
                            title = "App Updates",
                            subtitle = if (uiState.privacyMetrics.isUpdateAvailable) "New signed update available" else "Tap to check for updates",
                            isGood = !uiState.privacyMetrics.isUpdateAvailable,
                            onClick = { onNavigateToSecondary(SecondaryDestination.OtaUpdates) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricBox(title: String, count: Int, modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusRow(
    title: String,
    subtitle: String,
    isGood: Boolean,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGood) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
