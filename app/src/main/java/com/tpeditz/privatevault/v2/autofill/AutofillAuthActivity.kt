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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Dedicated secure activity for authenticating an Autofill request.
 * Displays a clean master password dialog to unlock the vault, verifies credentials,
 * and passes the matching dataset back to the Android Autofill framework.
 */
class AutofillAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val targetPackage = intent.getStringExtra("packageName").orEmpty()
        val targetDomain = intent.getStringExtra("targetDomain") ?: targetPackage.ifBlank { "Requested App" }

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
                                .fillMaxWidth(0.9f)
                                .padding(16.dp)
                        ) {
                            var password by remember { mutableStateOf("") }
                            var errorMessage by remember { mutableStateOf<String?>(null) }
                            var isAuthenticating by remember { mutableStateOf(false) }
                            val scope = rememberCoroutineScope()

                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    text = "Unlock Private Vault",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Enter master password to autofill credentials for $targetDomain",
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
                                                    val app = PrivateVaultApplication.instance
                                                    val authResult = app.repository.unlockVault(pwdChars)
                                                    when (authResult) {
                                                        is AuthResult.Success -> {
                                                            // Vault unlocked! Fetch credentials
                                                            val allItems = app.repository.getAllItemsForActiveSlot()
                                                            val credentials = allItems.filterIsInstance<CredentialRecord>()
                                                                .filter { !it.isArchived && !it.isTrashed }

                                                            val cleanTarget = AutofillHelper.normalizeDomain(targetDomain)
                                                            val directMatches = credentials.filter {
                                                                AutofillHelper.matchesTarget(it, cleanTarget, targetPackage)
                                                            }

                                                            val fillResponse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                PrivateVaultAutofillService.buildFillResponseForCredentials(
                                                                    context = applicationContext,
                                                                    packageName = targetPackage.ifBlank { packageName },
                                                                    usernameId = usernameId,
                                                                    passwordId = passwordId,
                                                                    credentials = directMatches
                                                                )
                                                            } else null

                                                            withContext(Dispatchers.Main) {
                                                                val replyIntent = Intent().apply {
                                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fillResponse != null) {
                                                                        putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse)
                                                                    }
                                                                }
                                                                setResult(Activity.RESULT_OK, replyIntent)
                                                                finish()
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
                                                        errorMessage = "Error unlocking vault: ${e.message}"
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
                                            Text("Unlock & Fill")
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
