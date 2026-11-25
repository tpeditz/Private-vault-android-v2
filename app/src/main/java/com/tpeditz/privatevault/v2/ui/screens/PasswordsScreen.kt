package com.tpeditz.privatevault.v2.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.data.CustomCredentialField
import com.tpeditz.privatevault.v2.data.PasswordHistoryEntry
import com.tpeditz.privatevault.v2.data.VaultFolder
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordGeneratorEngine
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultUiState
import com.tpeditz.privatevault.v2.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsScreen(
    uiState: VaultUiState,
    onNavigateToSecondary: (SecondaryDestination) -> Unit,
    onSaveCredential: (CredentialRecord) -> Unit,
    onDeleteCredential: (String, Boolean) -> Unit,
    onRestoreCredential: (String) -> Unit,
    onCopyPassword: (String, String) -> Unit,
    onSearchChange: (String) -> Unit,
    onPickCsvFile: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val filteredCredentials = uiState.credentials.filter { cred ->
        if (selectedFolderId != null && cred.folderId != selectedFolderId) return@filter false
        when (selectedFilter) {
            "Favorites" -> cred.isFavorite && !cred.isTrashed
            "Archived" -> cred.isArchived && !cred.isTrashed
            "Trash" -> cred.isTrashed
            else -> !cred.isTrashed && !cred.isArchived
        }
    }.filter { cred ->
        if (uiState.searchQuery.isBlank()) true else {
            cred.serviceName.contains(uiState.searchQuery, ignoreCase = true) ||
                cred.username.contains(uiState.searchQuery, ignoreCase = true) ||
                cred.accountLabel.contains(uiState.searchQuery, ignoreCase = true) ||
                cred.websiteUrl.contains(uiState.searchQuery, ignoreCase = true)
        }
    }.sortedWith(
        compareByDescending<CredentialRecord> { it.isPinned }
            .thenBy { it.serviceName.lowercase() }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Passwords", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                },
                actions = {
                    IconButton(onClick = { onNavigateToSecondary(SecondaryDestination.Tools) }) {
                        Icon(Icons.Default.Security, contentDescription = "Password Tools", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onPickCsvFile) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Import CSV", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToSecondary(SecondaryDestination.PasswordEditor()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Password")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search Input
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search services, usernames...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("All", "Favorites", "Archived", "Trash")
                items(filters) { f ->
                    FilterChip(
                        selected = selectedFilter == f,
                        onClick = { selectedFilter = f },
                        label = { Text(f) }
                    )
                }

                val credFolders = uiState.folders.filter { it.scope.name == "CREDENTIALS" }
                items(credFolders) { folder ->
                    FilterChip(
                        selected = selectedFolderId == folder.id,
                        onClick = {
                            selectedFolderId = if (selectedFolderId == folder.id) null else folder.id
                        },
                        label = { Text(folder.name) },
                        leadingIcon = {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            if (filteredCredentials.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No credentials match your search" else "No passwords saved yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredCredentials, key = { it.id }) { cred ->
                        CredentialCardItem(
                            credential = cred,
                            onClick = { onNavigateToSecondary(SecondaryDestination.PasswordEditor(cred.id)) },
                            onCopyPassword = { onCopyPassword(cred.serviceName, cred.password) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CredentialCardItem(
    credential: CredentialRecord,
    onClick: () -> Unit,
    onCopyPassword: () -> Unit
) {
    var isRevealed by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Neutral Avatar placeholder based on first letter of service
            val initial = credential.serviceName.firstOrNull()?.uppercase() ?: "K"
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = credential.serviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (credential.username.isNotBlank()) {
                    Text(
                        text = credential.username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRevealed) credential.password else "••••••••••••",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMono,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Quick actions
            IconButton(onClick = { isRevealed = !isRevealed }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isRevealed) "Hide password" else "Reveal password",
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onCopyPassword, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy password",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditorScreen(
    credentialId: String?,
    uiState: VaultUiState,
    onSaveCredential: (CredentialRecord) -> Unit,
    onDeleteCredential: (String) -> Unit,
    onRestorePassword: (String, PasswordHistoryEntry) -> Unit,
    onCopyPassword: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val existing = remember(credentialId) { uiState.credentials.find { it.id == credentialId } }

    var serviceName by remember { mutableStateOf(existing?.serviceName.orEmpty()) }
    var accountLabel by remember { mutableStateOf(existing?.accountLabel.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var websiteUrl by remember { mutableStateOf(existing?.websiteUrl.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var isFavorite by remember { mutableStateOf(existing?.isFavorite ?: false) }
    var isPinned by remember { mutableStateOf(existing?.isPinned ?: false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val customFields = remember {
        mutableStateListOf<CustomCredentialField>().apply {
            if (existing != null) addAll(existing.customFields)
        }
    }

    var showHistoryDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (credentialId == null) "New Credential" else "Edit Credential") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null && existing.passwordHistory.isNotEmpty()) {
                        IconButton(onClick = { showHistoryDialog = true }) {
                            Icon(Icons.Default.History, contentDescription = "Password History")
                        }
                    }
                    IconButton(onClick = { isPinned = !isPinned }) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pin",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { isFavorite = !isFavorite }) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                    if (existing != null) {
                        IconButton(onClick = {
                            onDeleteCredential(existing.id)
                            onBack()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OutlinedTextField(
                    value = serviceName,
                    onValueChange = { serviceName = it },
                    label = { Text("Service Name (e.g. Google, GitHub)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username / Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                            IconButton(
                                onClick = {
                                    val gen = PasswordGeneratorEngine.generate()
                                    if (gen is PasswordGeneratorEngine.GenerationResult.Success) {
                                        password = gen.password
                                        isPasswordVisible = true
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Generate")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (password.isNotBlank()) {
                    val strength = OfflinePasswordStrengthEstimator.evaluate(password)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Strength: ${strength.band.label} (${strength.score}/100)",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (strength.band) {
                            OfflinePasswordStrengthEstimator.StrengthBand.CRITICAL -> MaterialTheme.colorScheme.error
                            OfflinePasswordStrengthEstimator.StrengthBand.WEAK -> MaterialTheme.colorScheme.error
                            OfflinePasswordStrengthEstimator.StrengthBand.FAIR -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text("Website URL (for Autofill)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (serviceName.isNotBlank()) {
                            val record = (existing ?: CredentialRecord(
                                serviceName = serviceName,
                                username = username,
                                password = password
                            )).copy(
                                serviceName = serviceName,
                                accountLabel = accountLabel,
                                username = username,
                                password = password,
                                websiteUrl = websiteUrl,
                                notes = notes,
                                isFavorite = isFavorite,
                                isPinned = isPinned,
                                customFields = customFields.toList()
                            )
                            onSaveCredential(record)
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Credential")
                }
            }
        }
    }

    if (showHistoryDialog && existing != null) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Password History") },
            text = {
                LazyColumn {
                    items(existing.passwordHistory) { hist ->
                        val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                            .format(Date(hist.changedAtEpochMs))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(hist.password, fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold)
                                Text(dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { onCopyPassword(existing.serviceName, hist.password) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                            IconButton(onClick = {
                                password = hist.password
                                showHistoryDialog = false
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restore")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
