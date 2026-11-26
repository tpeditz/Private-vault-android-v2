package com.tpeditz.privatevault.v2.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.data.ChecklistEntry
import com.tpeditz.privatevault.v2.data.NoteBodyFormat
import com.tpeditz.privatevault.v2.data.NoteRecord
import com.tpeditz.privatevault.v2.data.VaultFolder
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    uiState: VaultUiState,
    onNavigateToSecondary: (SecondaryDestination) -> Unit,
    onSaveNote: (NoteRecord) -> Unit,
    onDeleteNote: (String, Boolean) -> Unit,
    onRestoreNote: (String) -> Unit,
    onToggleChecklist: (String, String) -> Unit,
    onSearchChange: (String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }

    val filteredNotes = remember(uiState.notes, selectedFolderId, selectedFilter, uiState.searchQuery) {
        uiState.notes.filter { note ->
            // Folder filter
            if (selectedFolderId != null && note.folderId != selectedFolderId) return@filter false

            // Category/Trash filter
            when (selectedFilter) {
                "Favorites" -> note.isFavorite && !note.isTrashed
                "Archived" -> note.isArchived && !note.isTrashed
                "Trash" -> note.isTrashed
                else -> !note.isTrashed && !note.isArchived
            }
        }.filter { note ->
            if (uiState.searchQuery.isBlank()) true else {
                note.title.contains(uiState.searchQuery, ignoreCase = true) ||
                    note.body.contains(uiState.searchQuery, ignoreCase = true) ||
                    note.tags.any { it.contains(uiState.searchQuery, ignoreCase = true) }
            }
        }.sortedWith(
            compareByDescending<NoteRecord> { it.isPinned }
                .thenByDescending { it.updatedAtEpochMs }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notes",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToSecondary(SecondaryDestination.NoteEditor()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Note")
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
                placeholder = { Text("Search notes...") },
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

                // Custom Folders
                val noteFolders = uiState.folders.filter { it.scope.name == "NOTES" }
                items(noteFolders) { folder ->
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

            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No notes match your search" else "No notes here yet. Tap + to create one.",
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
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCardItem(
                            note = note,
                            onClick = { onNavigateToSecondary(SecondaryDestination.NoteEditor(note.id)) },
                            onToggleChecklist = { entryId -> onToggleChecklist(note.id, entryId) },
                            onDelete = { onDeleteNote(note.id, false) },
                            onRestore = { onRestoreNote(note.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCardItem(
    note: NoteRecord,
    onClick: () -> Unit,
    onToggleChecklist: (String) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled Note" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (note.isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (note.format) {
                NoteBodyFormat.CHECKLIST -> {
                    val previewItems = note.checklistItems.take(3)
                    previewItems.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = entry.isChecked,
                                onCheckedChange = { onToggleChecklist(entry.id) },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (entry.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (entry.isChecked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (note.checklistItems.size > 3) {
                        Text(
                            text = "+ ${note.checklistItems.size - 3} more items",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                else -> {
                    if (note.body.isNotBlank()) {
                        Text(
                            text = note.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(note.updatedAtEpochMs))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                if (note.isTrashed) {
                    Row {
                        IconButton(onClick = onRestore, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String?,
    uiState: VaultUiState,
    onSaveNote: (NoteRecord, (Boolean, String?) -> Unit) -> Unit,
    onDeleteNote: (String) -> Unit,
    onBack: () -> Unit
) {
    val existingNote = remember(noteId) { uiState.notes.find { it.id == noteId } }
    val stableNoteId = remember(noteId) { existingNote?.id ?: java.util.UUID.randomUUID().toString() }
    val initialCreatedAtEpochMs = remember(noteId) { existingNote?.createdAtEpochMs ?: System.currentTimeMillis() }
    val initialTitle = remember(noteId) { existingNote?.title.orEmpty() }
    val initialBody = remember(noteId) { existingNote?.body.orEmpty() }
    val initialFormat = remember(noteId) { existingNote?.format ?: NoteBodyFormat.PLAIN_TEXT }
    val initialPinned = remember(noteId) { existingNote?.isPinned ?: false }
    val initialFavorite = remember(noteId) { existingNote?.isFavorite ?: false }
    val initialChecklist = remember(noteId) { existingNote?.checklistItems.orEmpty() }

    var title by remember(noteId) { mutableStateOf(initialTitle) }
    var body by remember(noteId) { mutableStateOf(initialBody) }
    var format by remember(noteId) { mutableStateOf(initialFormat) }
    var isPinned by remember(noteId) { mutableStateOf(initialPinned) }
    var isFavorite by remember(noteId) { mutableStateOf(initialFavorite) }

    val checklistEntries = remember(noteId) {
        mutableStateListOf<ChecklistEntry>().apply {
            addAll(initialChecklist)
        }
    }

    var newChecklistText by remember(noteId) { mutableStateOf("") }
    var isSaving by remember(noteId) { mutableStateOf(false) }
    var saveError by remember(noteId) { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember(noteId) { mutableStateOf(false) }
    val isEditorActive = remember(noteId) { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(noteId) {
        isEditorActive.value = true
        onDispose { isEditorActive.value = false }
    }

    val hasUnsavedChanges =
        title != initialTitle ||
            body != initialBody ||
            format != initialFormat ||
            checklistEntries.toList() != initialChecklist ||
            isPinned != initialPinned ||
            isFavorite != initialFavorite ||
            newChecklistText.isNotBlank()

    fun attemptBackNavigation() {
        if (isSaving) return
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    fun performExplicitSave() {
        if (isSaving) return
        val hasPendingChecklistInput = format == NoteBodyFormat.CHECKLIST && newChecklistText.isNotBlank()
        if (title.isBlank() && body.isBlank() && checklistEntries.isEmpty() && !hasPendingChecklistInput) {
            saveError = "Enter a note title, body, or checklist item before saving."
            return
        }
        if (hasPendingChecklistInput) {
            checklistEntries.add(ChecklistEntry(text = newChecklistText.trim()))
            newChecklistText = ""
        }
        isSaving = true
        saveError = null
        val base = existingNote ?: NoteRecord(
            id = stableNoteId,
            title = title,
            body = body,
            createdAtEpochMs = initialCreatedAtEpochMs
        )
        val record = base.copy(
            id = stableNoteId,
            title = title.ifBlank { "Untitled Note" },
            body = body,
            format = format,
            checklistItems = checklistEntries.toList(),
            isPinned = isPinned,
            isFavorite = isFavorite,
            createdAtEpochMs = initialCreatedAtEpochMs,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        onSaveNote(record) { success, errorMessage ->
            if (!isEditorActive.value) return@onSaveNote
            isSaving = false
            if (success) {
                onBack()
            } else {
                saveError = errorMessage ?: "Could not save note."
            }
        }
    }

    BackHandler(enabled = !isSaving) {
        attemptBackNavigation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "New Note" else "Edit Note") },
                navigationIcon = {
                    IconButton(onClick = { attemptBackNavigation() }, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isPinned = !isPinned
                            saveError = null
                        },
                        enabled = !isSaving
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pin",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(
                        onClick = {
                            isFavorite = !isFavorite
                            saveError = null
                        },
                        enabled = !isSaving
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                    if (existingNote != null) {
                        IconButton(
                            onClick = {
                                onDeleteNote(existingNote.id)
                                onBack()
                            },
                            enabled = !isSaving
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Format Selector Tabs
            TabRow(
                selectedTabIndex = format.ordinal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = format == NoteBodyFormat.PLAIN_TEXT,
                    onClick = { if (!isSaving) { format = NoteBodyFormat.PLAIN_TEXT; saveError = null } },
                    text = { Text("Plain Text") }
                )
                Tab(
                    selected = format == NoteBodyFormat.CHECKLIST,
                    onClick = { if (!isSaving) { format = NoteBodyFormat.CHECKLIST; saveError = null } },
                    text = { Text("Checklist") }
                )
                Tab(
                    selected = format == NoteBodyFormat.MARKDOWN,
                    onClick = { if (!isSaving) { format = NoteBodyFormat.MARKDOWN; saveError = null } },
                    text = { Text("Markdown") }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    saveError = null
                },
                label = { Text("Title") },
                enabled = !isSaving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            when (format) {
                NoteBodyFormat.CHECKLIST -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newChecklistText,
                            onValueChange = {
                                newChecklistText = it
                                saveError = null
                            },
                            placeholder = { Text("Add checklist item...") },
                            enabled = !isSaving,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (newChecklistText.isNotBlank()) {
                                    checklistEntries.add(ChecklistEntry(text = newChecklistText.trim()))
                                    newChecklistText = ""
                                    saveError = null
                                }
                            },
                            enabled = !isSaving
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Item")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(checklistEntries, key = { it.id }) { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = entry.isChecked,
                                    enabled = !isSaving,
                                    onCheckedChange = { checked ->
                                        val idx = checklistEntries.indexOf(entry)
                                        if (idx >= 0) {
                                            checklistEntries[idx] = entry.copy(isChecked = checked)
                                            saveError = null
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (entry.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        checklistEntries.remove(entry)
                                        saveError = null
                                    },
                                    enabled = !isSaving
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = body,
                        onValueChange = {
                            body = it
                            saveError = null
                        },
                        label = { Text(if (format == NoteBodyFormat.MARKDOWN) "Markdown Body" else "Note Body") },
                        enabled = !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }

            if (saveError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusLabel = when {
                    isSaving -> "Saving..."
                    hasUnsavedChanges -> "Unsaved changes"
                    existingNote == null -> "Not yet saved"
                    else -> "No unsaved changes"
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Button(
                    onClick = { performExplicitSave() },
                    enabled = !isSaving
                ) {
                    Text(if (isSaving) "Saving..." else "Save Note")
                }
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) showUnsavedDialog = false },
            title = { Text("Save changes?") },
            text = { Text("You have unsaved changes in this note.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        performExplicitSave()
                    },
                    enabled = !isSaving
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onBack()
                        },
                        enabled = !isSaving
                    ) {
                        Text("Discard")
                    }
                    TextButton(
                        onClick = { showUnsavedDialog = false },
                        enabled = !isSaving
                    ) {
                        Text("Keep editing")
                    }
                }
            }
        )
    }
}
