package com.tpeditz.privatevault.v2.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.data.DocumentRecord
import com.tpeditz.privatevault.v2.ui.VaultViewModel
import com.tpeditz.privatevault.v2.ui.cleanupIncompleteDocumentExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    documentId: String,
    viewModel: VaultViewModel,
    documentsList: List<DocumentRecord>,
    onDeleteDocument: (String) -> Unit,
    onBack: () -> Unit
) {
    val document = remember(documentId, documentsList) {
        documentsList.find { it.id == documentId }
    }

    if (document == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Document not found")
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var textPreview by remember(document.id) { mutableStateOf<String?>(null) }
    var hasPreviewContent by remember(document.id) { mutableStateOf(false) }
    var isExporting by remember(document.id) { mutableStateOf(false) }
    var exportJob by remember(document.id) { mutableStateOf<Job?>(null) }
    var loadError by remember(document.id) { mutableStateOf<String?>(null) }
    var isLoading by remember(document.id) { mutableStateOf(true) }

    val isTextDocument = remember(document.mimeType, document.originalFileName) {
        document.mimeType.startsWith("text/") ||
            document.mimeType.contains("json") ||
            document.mimeType.contains("xml") ||
            document.mimeType.contains("csv") ||
            document.originalFileName.endsWith(".txt", true) ||
            document.originalFileName.endsWith(".md", true) ||
            document.originalFileName.endsWith(".csv", true) ||
            document.originalFileName.endsWith(".json", true)
    }

    LaunchedEffect(document.id, isTextDocument) {
        isLoading = true
        loadError = null
        textPreview = null
        hasPreviewContent = false
        var rawPreview: ByteArray? = null
        try {
            val bytes = viewModel.loadDecryptedDocumentPreviewBytes(document, maxPreviewBytes = 512 * 1024)
            rawPreview = bytes
            ensureActive()
            hasPreviewContent = true
            if (isTextDocument) {
                textPreview = String(bytes, StandardCharsets.UTF_8).take(20000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Unable to open this encrypted document. Try unlocking your vault again."
        } finally {
            rawPreview?.let { Arrays.fill(it, 0.toByte()) }
            rawPreview = null
            isLoading = false
        }
    }

    DisposableEffect(document.id) {
        onDispose {
            exportJob?.cancel()
            exportJob = null
            textPreview = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(document.mimeType)
    ) { uri ->
        if (uri != null) {
            exportJob?.cancel()
            isExporting = true
            exportJob = scope.launch(Dispatchers.IO) {
                var exportCompleted = false
                try {
                    val outStream = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Unable to open destination file.")
                    outStream.use { os ->
                        val written = viewModel.exportDecryptedDocumentToStream(
                            documentRecord = document,
                            outputStream = os,
                            isCancelled = { !isActive }
                        )
                        ensureActive()
                        if (written != document.originalSizeBytes) {
                            throw java.io.IOException("Exported file size did not match encrypted document length.")
                        }
                        os.flush()
                    }
                    exportCompleted = true
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar("Document saved to chosen location (unencrypted copy)")
                    }
                } catch (e: CancellationException) {
                    val deleted = withContext(NonCancellable + Dispatchers.IO) {
                        cleanupIncompleteDocumentExport(exportCompleted) {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                    }
                    // Notification cancellation is not an export failure. Keep a file
                    // whose verified output stream has already closed successfully.
                    if (deleted != null && scope.isActive) {
                        withContext(NonCancellable + Dispatchers.Main) {
                            val cancelMsg = if (deleted) {
                                "Export cancelled. Incomplete file removed."
                            } else {
                                "Export cancelled. An incomplete file may remain at the chosen location."
                            }
                            runCatching { snackbarHostState.showSnackbar(cancelMsg) }
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    val deleted = withContext(NonCancellable + Dispatchers.IO) {
                        cleanupIncompleteDocumentExport(exportCompleted) {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                    }
                    if (deleted != null) {
                        val baseMsg = com.tpeditz.privatevault.v2.ui.VaultErrorMapper.mapFileExportError(e)
                        val msg = if (!deleted) {
                            "$baseMsg An incomplete file may remain at the chosen location."
                        } else {
                            baseMsg
                        }
                        withContext(NonCancellable + Dispatchers.Main) {
                            runCatching { snackbarHostState.showSnackbar(msg) }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        isExporting = false
                        exportJob = null
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Document") },
            text = { Text("Permanently delete this encrypted document? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteDocument(document.id)
                        onBack()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = document.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metadata Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = document.originalFileName.ifBlank { document.title },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    MetadataRow("Size", formatByteSize(document.originalSizeBytes))
                    MetadataRow("MIME Type", document.mimeType)
                    MetadataRow("Verified Chunks", "${document.chunkCount} chunk(s)")
                    MetadataRow("Category", document.category)
                    val dateFormatted = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(document.updatedAtEpochMs))
                    MetadataRow("Saved On", dateFormatted)

                    if (document.ciphertextSha256Hex.isNotBlank()) {
                        MetadataRow("Ciphertext SHA-256", document.ciphertextSha256Hex.take(16) + "…")
                    }
                }
            }

            // Export & Cancel Controls
            Button(
                onClick = {
                    viewModel.grantFilePickerImmunity()
                    exportLauncher.launch(document.originalFileName.ifBlank { "${document.title}.bin" })
                },
                enabled = !isLoading && !isExporting && loadError == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isExporting) "Exporting..." else "Export Decrypted Document")
            }
            if (isExporting) {
                TextButton(
                    onClick = { exportJob?.cancel() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel Export", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = "Note: Exported copies are unencrypted and saved outside Private Vault's protected storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Content Preview Section
            Text(
                text = "Document Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            val activePreview = textPreview
            val activeErr = loadError
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (activeErr != null) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = activeErr,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (activePreview != null) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = activePreview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Binary Document (${document.mimeType})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Authenticated and verified in memory with zero plaintext saved to storage. Tap Export Decrypted Document to save or open in an external viewer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
