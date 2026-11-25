package com.tpeditz.privatevault.v2.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tpeditz.privatevault.v2.PrivateVaultApplication
import com.tpeditz.privatevault.v2.data.AuthResult
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import com.tpeditz.privatevault.v2.ui.theme.PrivateVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

/**
 * Dedicated secure activity for authenticating an Autofill request and handling
 * deliberate manual credential selection when no strict automatic domain/app match exists.
 */
class AutofillAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val targetPackage = intent.getStringExtra("packageName").orEmpty()
        val rawDomain = intent.getStringExtra("targetDomain").orEmpty()
        val targetScheme = intent.getStringExtra("targetScheme").orEmpty()
        val displayTarget = if (rawDomain.isNotBlank()) "$rawDomain — reported by $targetPackage (website not verified)" else targetPackage.ifBlank { "this app" }

        val usernameId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("usernameId", AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("usernameId")
        }

        val passwordId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("passwordId", AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("passwordId")
        }

        val app = runCatching { PrivateVaultApplication.instance }.getOrNull()

        setContent {
            PrivateVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .padding(16.dp)
                        ) {
                            var password by remember { mutableStateOf("") }
                            var errorMessage by remember { mutableStateOf<String?>(null) }
                            var isAuthenticating by remember { mutableStateOf(false) }
                            var unlockedManualList by remember { mutableStateOf<List<CredentialRecord>?>(null) }
                            var searchQuery by remember { mutableStateOf("") }
                            var pickerSessionId by remember { mutableStateOf<Long?>(null) }
                            var pendingCredential by remember { mutableStateOf<CredentialRecord?>(null) }
                            val scope = rememberCoroutineScope()

                            suspend fun finishWithCredentials(selected: List<CredentialRecord>, expectedId: Long) {
                                val fillResponse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    PrivateVaultAutofillService.buildFillResponseForCredentials(
                                        context = applicationContext,
                                        packageName = targetPackage.ifBlank { packageName },
                                        usernameId = usernameId,
                                        passwordId = passwordId,
                                        credentials = selected
                                    )
                                } else null

                                withContext(Dispatchers.Main) {
                                    val replyIntent = Intent().apply {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fillResponse != null) {
                                            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse)
                                        }
                                    }
                                    val delivered = app?.sessionManager?.runIfCurrentSession(expectedId) {
                                        if (fillResponse != null) setResult(Activity.RESULT_OK, replyIntent)
                                        else setResult(Activity.RESULT_CANCELED)
                                        finish()
                                    } == true
                                    if (!delivered) {
                                        setResult(Activity.RESULT_CANCELED)
                                        finish()
                                    }
                                }
                            }

                            LaunchedEffect(Unit) {
                                if (app != null && app.sessionManager.isUnlocked()) {
                                    withContext(Dispatchers.IO) {
                                        val expectedId = app.sessionManager.getCurrentSessionId() ?: return@withContext
                                        val creds = app.repository.getAllItemsForActiveSlot(expectedId)
                                            .filterIsInstance<CredentialRecord>()
                                            .filter { !it.isArchived && !it.isTrashed }
                                        val cleanTarget = AutofillHelper.normalizeDomain(rawDomain)
                                        val directMatches = creds.filter {
                                            AutofillHelper.matchesTarget(it, cleanTarget, targetPackage, targetScheme.ifBlank { null })
                                        }
                                        if (directMatches.isNotEmpty()) {
                                            finishWithCredentials(directMatches, expectedId)
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                pickerSessionId = expectedId
                                                unlockedManualList = creds
                                            }
                                        }
                                    }
                                }
                            }

                            pendingCredential?.let { cred ->
                                AlertDialog(
                                    onDismissRequest = { pendingCredential = null },
                                    title = { Text("Confirm filling this login") },
                                    text = { Text("Fill ${cred.serviceName.ifBlank { "this login" }} in $displayTarget? Check the destination before continuing.") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            pendingCredential = null
                                            val expectedId = pickerSessionId
                                            if (expectedId != null) scope.launch { finishWithCredentials(listOf(cred), expectedId) }
                                        }) { Text("Fill once") }
                                    },
                                    dismissButton = { TextButton(onClick = { pendingCredential = null }) { Text("Cancel") } }
                                )
                            }
                            val pickerList = unlockedManualList
                            if (pickerList != null) {
                                val filtered = remember(pickerList, searchQuery) {
                                    if (searchQuery.isBlank()) pickerList
                                    else pickerList.filter {
                                        it.serviceName.contains(searchQuery, ignoreCase = true) ||
                                            it.username.contains(searchQuery, ignoreCase = true) ||
                                            it.websiteUrl.contains(searchQuery, ignoreCase = true)
                                    }
                                }
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text(
                                        text = "Choose a Login to Fill",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Select a login for $displayTarget. Only continue if you trust the destination.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = { Text("Search saved logins") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    if (filtered.isEmpty()) {
                                        Text(
                                            text = if (pickerList.isEmpty()) "Your vault has no saved logins yet." else "No matching logins found.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 16.dp)
                                        )
                                    } else {
                                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                                            items(filtered, key = { it.id }) { cred ->
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            pendingCredential = cred
                                                        }
                                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                                ) {
                                                    Text(cred.serviceName.ifBlank { "Saved Login" }, fontWeight = FontWeight.SemiBold)
                                                    Text(cred.username.ifBlank { "Account" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                HorizontalDivider()
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(
                                            onClick = {
                                                setResult(Activity.RESULT_CANCELED)
                                                finish()
                                            }
                                        ) {
                                            Text("Cancel")
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.padding(24.dp)) {
                                    Text(
                                        text = "Unlock Private Vault",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Enter your master password to check logins for $displayTarget",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = {
                                            password = it
                                            errorMessage = null
                                        },
                                        label = { Text("Vault Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        enabled = !isAuthenticating,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    if (errorMessage != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = errorMessage!!,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                setResult(Activity.RESULT_CANCELED)
                                                finish()
                                            },
                                            enabled = !isAuthenticating
                                        ) {
                                            Text("Cancel")
                                        }

                                        Button(
                                            onClick = {
                                                if (password.isBlank()) {
                                                    errorMessage = "Password cannot be empty"
                                                    return@Button
                                                }
                                                isAuthenticating = true
                                                val pwdChars = password.toCharArray()
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val application = app ?: PrivateVaultApplication.instance
                                                        val authResult = application.repository.unlockVault(pwdChars)
                                                        when (authResult) {
                                                            is AuthResult.Success -> {
                                                                val expectedId = application.sessionManager.getCurrentSessionId() ?: error("Vault is locked.")
                                                                val allItems = application.repository.getAllItemsForActiveSlot(expectedId)
                                                                val credentials = allItems.filterIsInstance<CredentialRecord>()
                                                                    .filter { !it.isArchived && !it.isTrashed }

                                                                val cleanTarget = AutofillHelper.normalizeDomain(rawDomain)
                                                                val directMatches = credentials.filter {
                                                                    AutofillHelper.matchesTarget(it, cleanTarget, targetPackage, targetScheme.ifBlank { null })
                                                                }
                                                                if (directMatches.isNotEmpty()) {
                                                                    finishWithCredentials(directMatches, expectedId)
                                                                } else {
                                                                    withContext(Dispatchers.Main) {
                                                                        password = ""
                                                                        isAuthenticating = false
                                                                        pickerSessionId = expectedId
                                                                        unlockedManualList = credentials
                                                                    }
                                                                }
                                                            }
                                                            is AuthResult.IncorrectPassword, is AuthResult.InvalidPassword -> {
                                                                withContext(Dispatchers.Main) {
                                                                    errorMessage = "Incorrect vault password"
                                                                    isAuthenticating = false
                                                                }
                                                            }
                                                            is AuthResult.LockedOut -> {
                                                                withContext(Dispatchers.Main) {
                                                                    errorMessage = "Too many failed attempts. Try again in ${authResult.remainingSeconds}s."
                                                                    isAuthenticating = false
                                                                }
                                                            }
                                                            else -> {
                                                                withContext(Dispatchers.Main) {
                                                                    errorMessage = "Authentication failed"
                                                                    isAuthenticating = false
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        SafeVaultLogger.e("AutofillAuth", "Unlock error", e)
                                                        withContext(Dispatchers.Main) {
                                                            errorMessage = "Could not unlock vault. Try again."
                                                            isAuthenticating = false
                                                        }
                                                    } finally {
                                                        Arrays.fill(pwdChars, '\u0000')
                                                    }
                                                }
                                            },
                                            enabled = !isAuthenticating
                                        ) {
                                            if (isAuthenticating) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.height(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            } else {
                                                Text("Unlock")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
