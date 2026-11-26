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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tpeditz.privatevault.v2.data.TotpAlgorithm
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpeditz.privatevault.v2.data.AuthenticatorRecord
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.security.OfflinePasswordStrengthEstimator
import com.tpeditz.privatevault.v2.security.PasswordGeneratorEngine
import com.tpeditz.privatevault.v2.security.TotpEngine
import com.tpeditz.privatevault.v2.ui.SecondaryDestination
import com.tpeditz.privatevault.v2.ui.VaultUiState
import com.tpeditz.privatevault.v2.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    uiState: VaultUiState,
    initialTab: Int = 0,
    onCopyPassword: (String, String) -> Unit,
    onSaveAuthenticator: (AuthenticatorRecord) -> Unit,
    onDeleteAuthenticator: (String) -> Unit,
    onNavigateToSecondary: (SecondaryDestination) -> Unit,
    onBack: () -> Unit
) {
    var selectedToolTab by remember { mutableIntStateOf(initialTab) } // 0: Generator, 1: Strength Audit, 2: Authenticator

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security Tools", fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            TabRow(
                selectedTabIndex = selectedToolTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Tab(selected = selectedToolTab == 0, onClick = { selectedToolTab = 0 }, text = { Text("Generator") })
                Tab(selected = selectedToolTab == 1, onClick = { selectedToolTab = 1 }, text = { Text("Strength Audit") })
                Tab(selected = selectedToolTab == 2, onClick = { selectedToolTab = 2 }, text = { Text("Authenticator") })
            }

            when (selectedToolTab) {
                0 -> GeneratorToolView(onCopyPassword)
                1 -> StrengthAuditToolView(uiState.credentials, onNavigateToSecondary)
                2 -> AuthenticatorToolView(
                    uiState = uiState,
                    onSaveAuthenticator = onSaveAuthenticator,
                    onDeleteAuthenticator = onDeleteAuthenticator,
                    onCopyCode = { name, code -> onCopyPassword(name, code) }
                )
            }
        }
    }
}

@Composable
fun GeneratorToolView(
    onCopyPassword: (String, String) -> Unit
) {
    var mode by remember { mutableStateOf("Password") } // "Password" or "Passphrase"
    var length by remember { mutableFloatStateOf(20f) }
    var uppercase by remember { mutableStateOf(true) }
    var lowercase by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var excludeAmbiguous by remember { mutableStateOf(true) }

    var wordCount by remember { mutableFloatStateOf(4f) }
    var separator by remember { mutableStateOf("-") }
    var capitalizeWords by remember { mutableStateOf(true) }
    var includeNumberInPassphrase by remember { mutableStateOf(true) }

    var generatedResult by remember { mutableStateOf("") }
    var entropyBits by remember { mutableStateOf(0.0) }

    fun refresh() {
        if (mode == "Password") {
            val res = PasswordGeneratorEngine.generate(
                PasswordGeneratorEngine.GeneratorConfig(
                    length = length.roundToInt(),
                    includeUppercase = uppercase,
                    includeLowercase = lowercase,
                    includeDigits = digits,
                    includeSymbols = symbols,
                    excludeAmbiguous = excludeAmbiguous
                )
            )
            if (res is PasswordGeneratorEngine.GenerationResult.Success) {
                generatedResult = res.password
                entropyBits = res.estimatedEntropyBits
            }
        } else {
            generatedResult = PasswordGeneratorEngine.generatePassphrase(
                wordCount = wordCount.roundToInt(),
                separator = separator,
                capitalize = capitalizeWords,
                includeNumber = includeNumberInPassphrase
            )
        }
    }

    LaunchedEffect(mode, length, uppercase, lowercase, digits, symbols, excludeAmbiguous, wordCount, separator, capitalizeWords, includeNumberInPassphrase) {
        refresh()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Display Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = generatedResult,
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (mode == "Password" && entropyBits > 0.0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Estimated entropy: ~${entropyBits.roundToInt()} bits",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (mode == "Passphrase") {
                        Spacer(modifier = Modifier.height(6.dp))
                        val passEval = remember(generatedResult) {
                            OfflinePasswordStrengthEstimator.evaluate(generatedResult)
                        }
                        Text(
                            text = "Strength: ${passEval.band.label} (~${passEval.estimatedEntropyBits} bits)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                        }
                        Button(onClick = { onCopyPassword("Generated Password", generatedResult) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "Password",
                    onClick = { mode = "Password" },
                    label = { Text("Standard Password") }
                )
                FilterChip(
                    selected = mode == "Passphrase",
                    onClick = { mode = "Passphrase" },
                    label = { Text("Memorable Passphrase") }
                )
            }
        }

        if (mode == "Password") {
            item {
                Text("Length: ${length.roundToInt()} characters", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = length,
                    onValueChange = { length = it },
                    valueRange = 8f..64f,
                    steps = 55
                )
            }

            item {
                val enabledClassCount = listOf(uppercase, lowercase, digits, symbols).count { it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Uppercase (A-Z)")
                    Switch(
                        checked = uppercase,
                        onCheckedChange = { if (it || enabledClassCount > 1) uppercase = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lowercase (a-z)")
                    Switch(
                        checked = lowercase,
                        onCheckedChange = { if (it || enabledClassCount > 1) lowercase = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Numbers (0-9)")
                    Switch(
                        checked = digits,
                        onCheckedChange = { if (it || enabledClassCount > 1) digits = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Symbols (!@#$)")
                    Switch(
                        checked = symbols,
                        onCheckedChange = { if (it || enabledClassCount > 1) symbols = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Exclude Ambiguous (0/O, 1/l)")
                    Switch(checked = excludeAmbiguous, onCheckedChange = { excludeAmbiguous = it })
                }
            }
        } else {
            item {
                Text("Words: ${wordCount.roundToInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = wordCount,
                    onValueChange = { wordCount = it },
                    valueRange = 4f..8f,
                    steps = 3
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Capitalize Words")
                    Switch(checked = capitalizeWords, onCheckedChange = { capitalizeWords = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Append Number")
                    Switch(checked = includeNumberInPassphrase, onCheckedChange = { includeNumberInPassphrase = it })
                }
            }
        }
    }
}

@Composable
fun StrengthAuditToolView(
    credentials: List<CredentialRecord>,
    onNavigateToSecondary: (SecondaryDestination) -> Unit
) {
    val activeCreds = credentials.filter { !it.isTrashed && !it.isArchived }
    val passwords = activeCreds.map { it.password }
    val reuseMap = OfflinePasswordStrengthEstimator.computeVaultReuseFrequencyMap(passwords)

    val weakItems = activeCreds.filter { cred ->
        val report = OfflinePasswordStrengthEstimator.evaluate(cred.password)
        report.band == OfflinePasswordStrengthEstimator.StrengthBand.CRITICAL ||
            report.band == OfflinePasswordStrengthEstimator.StrengthBand.WEAK
    }

    val reusedItems = activeCreds.filter { (reuseMap[it.password] ?: 0) > 1 }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Vault Security Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${activeCreds.size} active credentials analyzed")
                    Text("${weakItems.size} weak passwords detected", color = MaterialTheme.colorScheme.error)
                    Text("${reusedItems.size} reused accounts detected", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        if (weakItems.isNotEmpty()) {
            item {
                Text("Weak Passwords to Update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(weakItems, key = { it.id }) { cred ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSecondary(SecondaryDestination.PasswordEditor(cred.id)) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cred.serviceName, fontWeight = FontWeight.SemiBold)
                            Text(cred.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (reusedItems.isNotEmpty()) {
            item {
                Text("Reused Passwords", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(reusedItems, key = { it.id }) { cred ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSecondary(SecondaryDestination.PasswordEditor(cred.id)) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cred.serviceName, fontWeight = FontWeight.SemiBold)
                            Text(cred.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Reused", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun AuthenticatorToolView(
    uiState: VaultUiState,
    onSaveAuthenticator: (AuthenticatorRecord) -> Unit,
    onDeleteAuthenticator: (String) -> Unit,
    onCopyCode: (String, String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteAuth by remember { mutableStateOf<AuthenticatorRecord?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Authenticator")
            }
        }
    ) { innerPadding ->
        if (uiState.authenticators.isEmpty()) {
            EmptyMediaState("No authenticator accounts added yet. Tap + to add one.", Icons.Default.Timer)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.authenticators, key = { it.id }) { auth ->
                    val safePeriod = auth.periodSeconds.coerceIn(TotpEngine.MIN_TOTP_PERIOD_SECONDS, TotpEngine.MAX_TOTP_PERIOD_SECONDS)
                    val remainingSec = TotpEngine.getRemainingSeconds(
                        epochSeconds = uiState.totpEpochSeconds,
                        periodSeconds = safePeriod
                    )
                    val code = runCatching {
                        TotpEngine.generateTotpCode(
                            secretBase32 = auth.secret,
                            epochSeconds = uiState.totpEpochSeconds,
                            periodSeconds = auth.periodSeconds,
                            digits = auth.digits,
                            algorithm = auth.algorithm
                        )
                    }.getOrNull()
                    val formattedCode = if (code != null) {
                        val groupSize = if (code.length == 8) 4 else 3
                        code.chunked(groupSize).joinToString(" ")
                    } else {
                        "Invalid"
                    }

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Circular countdown progress bound to this item's period
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { (remainingSec.toFloat() / safePeriod.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                                Text(
                                    text = "${remainingSec}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(auth.serviceName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (auth.accountName.isNotBlank()) {
                                    Text(auth.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formattedCode,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (code != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }

                            IconButton(
                                onClick = { if (code != null) onCopyCode(auth.serviceName, code) },
                                enabled = code != null
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code")
                            }
                            IconButton(onClick = { pendingDeleteAuth = auth }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Authenticator", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val confirmTarget = pendingDeleteAuth
    if (confirmTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteAuth = null },
            title = { Text("Delete Authenticator?") },
            text = {
                Text("Remove ${confirmTarget.serviceName}? Make sure you have another 2FA backup option before deleting.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAuthenticator(confirmTarget.id)
                        pendingDeleteAuth = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAuth = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (showAddDialog) {
        var service by remember { mutableStateOf("") }
        var account by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var parsedDigits by remember { mutableIntStateOf(6) }
        var parsedPeriod by remember { mutableIntStateOf(30) }
        var parsedAlgo by remember { mutableStateOf(TotpAlgorithm.SHA1) }
        var scanError by remember { mutableStateOf<String?>(null) }

        val qrPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { decodeBoundedQrBitmap(context, uri) }
                    if (bitmap == null) {
                        scanError = "Could not open or decode that image."
                        return@launch
                    }
                    val qrText = withContext(Dispatchers.Default) {
                        try {
                            decodeQrFromBitmap(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (!qrText.isNullOrBlank()) {
                        when (val parsed = TotpEngine.parseOtpAuthUriWithDetails(qrText)) {
                            is TotpEngine.ParseOtpAuthResult.Success -> {
                                service = parsed.parsed.serviceName
                                account = parsed.parsed.accountName
                                secret = parsed.parsed.secret
                                parsedDigits = parsed.parsed.digits
                                parsedPeriod = parsed.parsed.periodSeconds
                                parsedAlgo = parsed.parsed.algorithm
                                scanError = null
                            }
                            is TotpEngine.ParseOtpAuthResult.Invalid -> {
                                scanError = parsed.message
                            }
                        }
                    } else {
                        scanError = "No QR code detected in image."
                    }
                }
            }
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Authenticator (TOTP)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            qrPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR Code Image")
                    }

                    if (scanError != null) {
                        Text(scanError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedTextField(
                        value = service,
                        onValueChange = { service = it; scanError = null },
                        label = { Text("Service (e.g. GitHub, Google)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text("Account (e.g. user@example.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it; scanError = null },
                        label = { Text("Secret Key (Base32)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$parsedDigits digits • ${parsedPeriod}s • ${parsedAlgo.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalizedSecret = secret.replace(" ", "").replace("-", "").trim().uppercase(java.util.Locale.US)
                        when {
                            service.isBlank() -> scanError = "Enter a service name."
                            normalizedSecret.isBlank() -> scanError = "Enter the Base32 secret key."
                            else -> {
                                val decoded = runCatching { TotpEngine.decodeBase32(normalizedSecret) }.getOrNull()
                                if (decoded == null || decoded.isEmpty()) {
                                    scanError = "Invalid Base32 secret key. Use letters A-Z and digits 2-7."
                                } else {
                                    onSaveAuthenticator(
                                        AuthenticatorRecord(
                                            serviceName = service.trim(),
                                            accountName = account.trim(),
                                            secret = normalizedSecret,
                                            digits = parsedDigits,
                                            periodSeconds = parsedPeriod,
                                            algorithm = parsedAlgo
                                        )
                                    )
                                    showAddDialog = false
                                }
                            }
                        }
                    }
                ) {
                    Text("Add Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun decodeBoundedQrBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    val maxDim = 1024
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.isMutableRequired = true
                val w = info.size.width
                val h = info.size.height
                if (w > maxDim || h > maxDim) {
                    val scale = maxOf(w, h).toFloat() / maxDim.toFloat()
                    decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            val sample = if (maxSide > maxDim) (maxSide / maxDim).coerceAtLeast(1) else 1
            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binaryBitmap).text
    } catch (_: Exception) {
        null
    }
}

fun parseOtpAuthUri(uriStr: String): Triple<String, String, String>? {
    val parsed = TotpEngine.parseOtpAuthUri(uriStr) ?: return null
    return Triple(parsed.serviceName, parsed.accountName, parsed.secret)
}
