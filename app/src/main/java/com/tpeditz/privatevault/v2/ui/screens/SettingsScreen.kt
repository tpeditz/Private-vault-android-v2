package com.tpeditz.privatevault.v2.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.data.ThemePreference
import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultUiState
import com.tpeditz.privatevault.v2.ui.theme.JetBrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: VaultUiState,
    onNavigateToSecondary: (SecondaryDestination) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetScreenshotProtection: (Boolean) -> Unit,
    onSetAutoLockTimeout: (Int) -> Unit
) {
    val context = LocalContext.current
    var showBenchmarkDialog by remember { mutableStateOf(false) }
    var benchmarkResult by remember { mutableStateOf<CryptoEngine.KdfBenchmarkResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
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
            // Backup & Cloud Section
            item {
                SettingsCategoryTitle("Backup & Restore")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        SettingsRow(
                            icon = Icons.Default.SaveAlt,
                            title = "Local Backup & Restore",
                            subtitle = "Portable encrypted .pvault (Up to 2 GB)",
                            onClick = { onNavigateToSecondary(SecondaryDestination.BackupAndRestore) }
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Default.Cloud,
                            title = "Google Drive Cloud Backup",
                            subtitle = "Zero-knowledge encrypted snapshots",
                            onClick = { onNavigateToSecondary(SecondaryDestination.CloudBackup) }
                        )
                    }
                }
            }

            // Security & Privacy
            item {
                SettingsCategoryTitle("Security & Privacy")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        SettingsRow(
                            icon = Icons.Default.Security,
                            title = "Privacy & Advanced Security",
                            subtitle = "Biometrics, Auto-Lock, Decoy Vault",
                            onClick = { onNavigateToSecondary(SecondaryDestination.SecuritySettings) }
                        )
                    }
                }
            }

            // Appearance & Updates
            item {
                SettingsCategoryTitle("Appearance & Updates")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text("Theme Mode", fontWeight = FontWeight.SemiBold)
                                    Text(uiState.themePreference.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (uiState.themePreference == ThemePreference.SYSTEM) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { onSetTheme(ThemePreference.SYSTEM) }
                                ) {
                                    Text("Auto", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (uiState.themePreference == ThemePreference.LIGHT) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { onSetTheme(ThemePreference.LIGHT) }
                                ) {
                                    Text("Light", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (uiState.themePreference == ThemePreference.DARK) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { onSetTheme(ThemePreference.DARK) }
                                ) {
                                    Text("Dark", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Default.SystemUpdate,
                            title = "App Updates",
                            subtitle = "Version ${BuildConfig.VERSION_NAME} • Channel: ${if (uiState.betaChannelOptIn && uiState.betaEnrollmentAllowed) "Beta" else "Stable"}",
                            onClick = { onNavigateToSecondary(SecondaryDestination.OtaUpdates) }
                        )
                    }
                }
            }

            // About & Performance
            item {
                SettingsCategoryTitle("About & Performance")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        SettingsRow(
                            icon = Icons.Default.Info,
                            title = "About Private Vault",
                            subtitle = "Developed by Tp Editz • Support & Privacy",
                            onClick = { onNavigateToSecondary(SecondaryDestination.About) }
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Default.Feedback,
                            title = "Send Feedback",
                            subtitle = if (uiState.feedbackState.isSubmissionAllowed) {
                                "Optional private feedback"
                            } else {
                                "Privacy-first preview (Disabled pending server deployment)"
                            },
                            onClick = { onNavigateToSecondary(SecondaryDestination.Feedback) }
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Default.Speed,
                            title = "KDF Performance Benchmark",
                            subtitle = "Measure device Argon2id derivation latency",
                            onClick = {
                                benchmarkResult = CryptoEngine.measureKdfBenchmark()
                                showBenchmarkDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showBenchmarkDialog && benchmarkResult != null) {
        val b = benchmarkResult!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBenchmarkDialog = false },
            title = { Text("Argon2id KDF Benchmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device: ${b.deviceModel}")
                    Text("Memory per slot: ${b.memoryKbPerSlot / 1024} MiB")
                    Text("Iterations: ${b.iterations} | Parallelism: ${b.parallelism}")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Single-slot duration: ${b.singleSlotDurationMs} ms", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Two-slot duration: ${b.twoSlotUnlockDurationMs} ms")
                    Text("Heap peak: ${b.usedHeapMbAfterRun} MB / ${b.maxHeapMb} MB")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showBenchmarkDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun SettingsCategoryTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}
