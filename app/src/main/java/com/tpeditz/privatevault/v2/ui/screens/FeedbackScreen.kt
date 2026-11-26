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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.policy.PolicyFeedbackState
import com.tpeditz.privatevault.v2.policy.PolicyFormatters
import com.tpeditz.privatevault.v2.policy.ReleaseChannel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    feedbackState: PolicyFeedbackState,
    betaChannelOptIn: Boolean,
    onBack: () -> Unit,
    onSubmitFeedback: (
        category: String,
        message: String,
        contact: String?,
        consentChecked: Boolean,
        onComplete: (Boolean, String) -> Unit
    ) -> Unit = { _, _, _, _, cb ->
        cb(false, "Feedback submission is disabled pending server endpoint deployment.")
    },
    onSubmitDisabledNotice: (String) -> Unit = {}
) {
    var category by remember { mutableStateOf("suggestion") }
    var message by remember { mutableStateOf("") }
    var optionalContact by remember { mutableStateOf("") }
    var consentChecked by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var submissionSuccess by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }

    val channelLabel = if (betaChannelOptIn) ReleaseChannel.BETA.wireValue else ReleaseChannel.STABLE.wireValue

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Feedback", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!feedbackState.isSubmissionAllowed) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Feedback Submission Disabled", fontWeight = FontWeight.Bold)
                                Text(
                                    feedbackState.disabledReason
                                        ?: "Feedback submission is disabled pending server endpoint deployment.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (submissionSuccess != null) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(submissionSuccess!!, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Feedback,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Privacy-First Feedback",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "Private Vault never collects or sends vault entries, encryption keys, device hardware IDs, or background logs. Only the category, your message, app version (${BuildConfig.VERSION_NAME}), and optional contact text would be submitted after explicit consent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("Category", fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "bug" to "Bug",
                                "suggestion" to "Suggestion",
                                "other" to "Other"
                            ).forEach { (key, label) ->
                                FilterChip(
                                    selected = category == key,
                                    onClick = { category = key; validationError = null },
                                    label = { Text(label) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = message,
                            onValueChange = {
                                if (it.length <= PolicyFormatters.MAX_FEEDBACK_LENGTH) {
                                    message = it
                                    validationError = null
                                    submissionSuccess = null
                                }
                            },
                            label = { Text("Message (1-${PolicyFormatters.MAX_FEEDBACK_LENGTH} chars)") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${message.trim().length} / ${PolicyFormatters.MAX_FEEDBACK_LENGTH}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        OutlinedTextField(
                            value = optionalContact,
                            onValueChange = {
                                if (it.length <= PolicyFormatters.MAX_CONTACT_LENGTH) {
                                    optionalContact = it
                                    validationError = null
                                }
                            },
                            label = { Text("Optional reply handle (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = consentChecked,
                                onCheckedChange = { consentChecked = it; validationError = null }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "I explicitly consent to submitting this feedback message with app version ${BuildConfig.VERSION_NAME}.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (validationError != null) {
                            Text(
                                validationError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val check = PolicyFormatters.validateFeedbackInput(
                                        category = category,
                                        message = message,
                                        contact = optionalContact,
                                        consentChecked = consentChecked
                                    )
                                    if (!check.isValid) {
                                        validationError = check.errorMessage
                                    } else {
                                        showPreviewDialog = true
                                    }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Preview")
                            }

                            Button(
                                onClick = {
                                    if (!feedbackState.isSubmissionAllowed) {
                                        onSubmitDisabledNotice(
                                            feedbackState.disabledReason
                                                ?: "Feedback submission is disabled pending server endpoint deployment."
                                        )
                                        return@Button
                                    }
                                    val check = PolicyFormatters.validateFeedbackInput(
                                        category = category,
                                        message = message,
                                        contact = optionalContact,
                                        consentChecked = consentChecked
                                    )
                                    if (!check.isValid) {
                                        validationError = check.errorMessage
                                        return@Button
                                    }
                                    isSubmitting = true
                                    validationError = null
                                    submissionSuccess = null
                                    onSubmitFeedback(category, message, optionalContact, consentChecked) { ok, msg ->
                                        isSubmitting = false
                                        if (ok) {
                                            submissionSuccess = msg
                                            message = ""
                                            optionalContact = ""
                                            consentChecked = false
                                        } else {
                                            validationError = msg
                                        }
                                    }
                                },
                                enabled = feedbackState.isSubmissionAllowed && !isSubmitting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Send")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = { Text("Feedback Payload Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category: $category", style = MaterialTheme.typography.bodySmall)
                    Text("Version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Text("Local Channel Preference: $channelLabel (not stored unless included)", style = MaterialTheme.typography.bodySmall)
                    if (optionalContact.isNotBlank()) {
                        Text("Optional contact: ${PolicyFormatters.sanitizePlainText(optionalContact, PolicyFormatters.MAX_CONTACT_LENGTH)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Message:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(
                        PolicyFormatters.sanitizePlainText(message, 1200),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!feedbackState.isSubmissionAllowed) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            feedbackState.disabledReason
                                ?: "Feedback submission is disabled pending server endpoint deployment.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreviewDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
