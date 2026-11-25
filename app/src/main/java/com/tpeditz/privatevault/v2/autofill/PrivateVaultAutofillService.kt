package com.tpeditz.privatevault.v2.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tpeditz.privatevault.v2.PrivateVaultApplication
import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI

@RequiresApi(Build.VERSION_CODES.O)
class PrivateVaultAutofillService : AutofillService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        fun isAutofillServiceEnabled(context: Context): Boolean =
            AutofillHelper.isAutofillServiceEnabled(context)

        fun normalizeDomain(url: String): String =
            AutofillHelper.normalizeDomain(url)

        fun matchesTarget(
            cred: CredentialRecord,
            cleanTargetDomain: String,
            packageName: String
        ): Boolean = AutofillHelper.matchesTarget(cred, cleanTargetDomain, packageName)

        fun buildFillResponseForCredentials(
            context: Context,
            packageName: String,
            usernameId: AutofillId?,
            passwordId: AutofillId?,
            credentials: List<CredentialRecord>
        ): FillResponse? {
            if (credentials.isEmpty() || (usernameId == null && passwordId == null)) {
                return null
            }
            val responseBuilder = FillResponse.Builder()
            var count = 0

            for (cred in credentials) {
                val presentation = RemoteViews(context.packageName, android.R.layout.simple_list_item_2).apply {
                    setTextViewText(android.R.id.text1, cred.serviceName.ifBlank { "Private Vault" })
                    setTextViewText(android.R.id.text2, cred.username.ifBlank { "Account" })
                }

                val datasetBuilder = Dataset.Builder(presentation)
                usernameId?.let {
                    datasetBuilder.setValue(it, AutofillValue.forText(cred.username))
                }
                passwordId?.let {
                    datasetBuilder.setValue(it, AutofillValue.forText(cred.password))
                }
                responseBuilder.addDataset(datasetBuilder.build())
                count++
                if (count >= 5) break
            }

            val requiredIds = listOfNotNull(usernameId, passwordId).toTypedArray()
            if (requiredIds.isNotEmpty()) {
                val saveInfo = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, requiredIds).build()
                responseBuilder.setSaveInfo(saveInfo)
            }

            return if (count > 0) responseBuilder.build() else null
        }
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        val parsedFields = AutofillStructureParser.parse(structure)
        if (parsedFields.usernameId == null && parsedFields.passwordId == null) {
            callback.onSuccess(null)
            return
        }

        val packageName = structure.activityComponent.packageName
        if (packageName == this.packageName) {
            callback.onSuccess(null)
            return
        }
        val targetDomain = parsedFields.webDomain.orEmpty()

        val app = runCatching { PrivateVaultApplication.instance }.getOrNull()
        if (app == null) {
            callback.onSuccess(null)
            return
        }

        if (!app.sessionManager.isUnlocked()) {
            // Vault is locked: Present neutral authentication prompt
            val unlockIntent = Intent(this, AutofillAuthActivity::class.java).apply {
                putExtra("packageName", packageName)
                putExtra("targetDomain", targetDomain)
                putExtra("usernameId", parsedFields.usernameId)
                putExtra("passwordId", parsedFields.passwordId)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                1001,
                unlockIntent,
                flags
            )

            val remoteViews = RemoteViews(this.packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Unlock Private Vault to Autofill")
            }

            val response = FillResponse.Builder()
                .setAuthentication(
                    arrayOf(parsedFields.usernameId ?: parsedFields.passwordId!!),
                    pendingIntent.intentSender,
                    remoteViews
                )
                .build()

            callback.onSuccess(response)
            return
        }

        // Vault is unlocked: Look up authentic credentials
        serviceScope.launch {
            try {
                val allItems = app.repository.getAllItemsForActiveSlot()
                val credentials = allItems.filterIsInstance<CredentialRecord>()
                    .filter { !it.isArchived && !it.isTrashed }

                val cleanTarget = normalizeDomain(targetDomain)
                val directMatches = credentials.filter { matchesTarget(it, cleanTarget, packageName) }

                val fillResponse = buildFillResponseForCredentials(
                    context = applicationContext,
                    packageName = packageName,
                    usernameId = parsedFields.usernameId,
                    passwordId = parsedFields.passwordId,
                    credentials = directMatches
                )

                callback.onSuccess(fillResponse)
            } catch (e: Exception) {
                SafeVaultLogger.e("Autofill", "Fill request failed", e)
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val app = runCatching { PrivateVaultApplication.instance }.getOrNull()
        if (app == null || !app.sessionManager.isUnlocked()) {
            callback.onSuccess()
            return
        }

        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess()
            return
        }
        if (structure.activityComponent.packageName == this.packageName) {
            callback.onSuccess()
            return
        }

        val parsed = AutofillStructureParser.parse(structure)
        val usernameVal = parsed.extractedUsername?.trim().orEmpty()
        val passwordVal = parsed.extractedPassword.orEmpty()

        if (passwordVal.isNotBlank()) {
            serviceScope.launch {
                try {
                    val serviceName = parsed.webDomain?.let { normalizeDomain(it) }
                        ?.ifBlank { structure.activityComponent.packageName }
                        ?: structure.activityComponent.packageName

                    val newCred = CredentialRecord(
                        serviceName = serviceName,
                        username = usernameVal,
                        password = passwordVal,
                        websiteUrl = parsed.webDomain.orEmpty()
                    )
                    app.repository.saveItem(newCred)
                    SafeVaultLogger.i("Autofill", "Saved autofilled credential for $serviceName")
                } catch (e: Exception) {
                    SafeVaultLogger.w("Autofill", "Failed to save credential: ${e.message}")
                } finally {
                    callback.onSuccess()
                }
            }
        } else {
            callback.onSuccess()
        }
    }
}

/**
 * Parser for Android AssistStructure to locate username and password inputs.
 */
@RequiresApi(Build.VERSION_CODES.O)
object AutofillStructureParser {

    data class ParsedFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val webDomain: String?,
        val extractedUsername: String? = null,
        val extractedPassword: String? = null
    )

    fun parse(structure: android.app.assist.AssistStructure): ParsedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var webDomain: String? = null
        var extractedUsername: String? = null
        var extractedPassword: String? = null

        val nodeCount = structure.windowNodeCount
        for (i in 0 until nodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val root = windowNode.rootViewNode
            val res = traverseNode(root)
            if (res.usernameId != null) usernameId = res.usernameId
            if (res.passwordId != null) passwordId = res.passwordId
            if (res.webDomain != null) webDomain = res.webDomain
            if (res.extractedUsername != null) extractedUsername = res.extractedUsername
            if (res.extractedPassword != null) extractedPassword = res.extractedPassword
        }

        return ParsedFields(usernameId, passwordId, webDomain, extractedUsername, extractedPassword)
    }

    private fun traverseNode(node: android.app.assist.AssistStructure.ViewNode): ParsedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var webDomain: String? = node.webDomain
        var extractedUsername: String? = null
        var extractedPassword: String? = null

        val hints = node.autofillHints ?: emptyArray()
        val idEntry = node.idEntry?.lowercase() ?: ""

        val isPassword = hints.contains(android.view.View.AUTOFILL_HINT_PASSWORD) ||
            idEntry.contains("password") || idEntry.contains("pwd")

        val isUsername = hints.contains(android.view.View.AUTOFILL_HINT_USERNAME) ||
            hints.contains(android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS) ||
            idEntry.contains("username") || idEntry.contains("email") || idEntry.contains("login")

        if (isPassword) {
            passwordId = node.autofillId
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) extractedPassword = value
        } else if (isUsername) {
            usernameId = node.autofillId
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) extractedUsername = value
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            val childRes = traverseNode(child)
            if (usernameId == null && childRes.usernameId != null) usernameId = childRes.usernameId
            if (passwordId == null && childRes.passwordId != null) passwordId = childRes.passwordId
            if (webDomain == null && childRes.webDomain != null) webDomain = childRes.webDomain
            if (extractedUsername == null && childRes.extractedUsername != null) extractedUsername = childRes.extractedUsername
            if (extractedPassword == null && childRes.extractedPassword != null) extractedPassword = childRes.extractedPassword
        }

        return ParsedFields(usernameId, passwordId, webDomain, extractedUsername, extractedPassword)
    }
}
