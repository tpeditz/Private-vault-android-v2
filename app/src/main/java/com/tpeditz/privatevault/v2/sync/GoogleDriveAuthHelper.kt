package com.tpeditz.privatevault.v2.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern Google Identity & Drive Authorization helper for PRIVATE VAULT V2.0.1:
 * - Authentication (account selection & identity) uses AndroidX Credential Manager (`GoogleIdTokenCredential`).
 * - OAuth2 `drive.appdata` permission & token issuance uses Google Identity Services `AuthorizationClient`.
 * - Account connection never unlocks the local vault and uses minimal `drive.appdata` scope only.
 */
object GoogleDriveAuthHelper {

    sealed interface DriveAuthState {
        data class Authorized(val accessToken: String, val accountEmail: String) : DriveAuthState
        data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthState
        data class Failed(val userMessage: String, val requiresReconnect: Boolean = false) : DriveAuthState
    }

    fun isGoogleDriveConfigured(): Boolean {
        return BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com")
    }

    fun isPlayServicesAvailable(context: Context): Boolean {
        return runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    suspend fun signInWithCredentialManager(activityContext: Context): Result<String> {
        if (!isGoogleDriveConfigured()) {
            return Result.failure(IllegalStateException("Google Drive backup is not enabled in this build."))
        }
        if (!isPlayServicesAvailable(activityContext)) {
            return Result.failure(IllegalStateException("Google Play Services is unavailable on this device."))
        }
        return try {
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val credentialManager = CredentialManager.create(activityContext)
            val result = credentialManager.getCredential(activityContext, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                val email = googleCred.id.trim()
                if (email.isNotBlank()) {
                    Result.success(email)
                } else {
                    Result.failure(Exception("Google sign-in returned no account email."))
                }
            } else {
                Result.failure(Exception("Unsupported Google sign-in response. Try again."))
            }
        } catch (_: GetCredentialCancellationException) {
            Result.failure(Exception("Google sign-in was cancelled."))
        } catch (_: NoCredentialException) {
            Result.failure(Exception("No Google account found on this device. Add a Google account in Android Settings and try again."))
        } catch (e: GetCredentialException) {
            SafeVaultLogger.w("GoogleAuth", "Credential Manager sign-in unavailable: ${e.type}")
            Result.failure(Exception("Could not sign in with Google right now. Check your connection and try again."))
        } catch (e: Exception) {
            SafeVaultLogger.w("GoogleAuth", "Unexpected Credential Manager failure", e)
            Result.failure(Exception("Could not connect to Google. Try again later."))
        }
    }

    suspend fun requestOrFetchDriveAccessToken(
        context: Context,
        accountEmail: String
    ): DriveAuthState = withContext(Dispatchers.IO) {
        if (!isGoogleDriveConfigured()) {
            return@withContext DriveAuthState.Failed("Google Drive backup is not enabled in this build.")
        }
        if (accountEmail.isBlank()) {
            return@withContext DriveAuthState.Failed("Connect a Google account first.", requiresReconnect = true)
        }
        if (!isPlayServicesAvailable(context)) {
            return@withContext DriveAuthState.Failed("Google Play Services is unavailable on this device.")
        }
        return@withContext try {
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope(GoogleDriveSyncManager.SCOPE_DRIVE_APPDATA)))
                .setAccount(Account(accountEmail, "com.google"))
                .build()
            val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
            val pendingIntent = result.pendingIntent
            val token = result.accessToken
            when {
                result.hasResolution() && pendingIntent != null -> DriveAuthState.NeedsConsent(pendingIntent)
                !token.isNullOrBlank() -> DriveAuthState.Authorized(token, accountEmail)
                else -> DriveAuthState.Failed("Reconnect your Google account to continue.", requiresReconnect = true)
            }
        } catch (e: ApiException) {
            SafeVaultLogger.w("GoogleAuth", "AuthorizationClient ApiException status=${e.statusCode}")
            val message = when (e.statusCode) {
                7 -> "Check your internet connection and try again."
                4, 16, 17 -> "Reconnect your Google account to continue."
                else -> "Could not verify Google Drive authorization. Reconnect your Google account and try again."
            }
            DriveAuthState.Failed(message, requiresReconnect = e.statusCode != 7)
        } catch (e: Exception) {
            SafeVaultLogger.w("GoogleAuth", "AuthorizationClient failed", e)
            DriveAuthState.Failed("Check your internet connection or reconnect your Google account.")
        }
    }

    fun parseAuthorizationConsentResult(
        context: Context,
        data: Intent?,
        accountEmail: String
    ): DriveAuthState {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                DriveAuthState.Authorized(token, accountEmail)
            } else {
                DriveAuthState.Failed("Google Drive access was not granted.", requiresReconnect = true)
            }
        } catch (e: ApiException) {
            SafeVaultLogger.w("GoogleAuth", "Consent result ApiException status=${e.statusCode}")
            if (e.statusCode == 12501 || e.statusCode == 16) {
                DriveAuthState.Failed("Google Drive permission was cancelled.")
            } else {
                DriveAuthState.Failed("Could not grant Google Drive access. Try reconnecting.", requiresReconnect = true)
            }
        } catch (_: Exception) {
            DriveAuthState.Failed("Could not complete Google Drive authorization.")
        }
    }

    suspend fun signOut(context: Context): Unit = withContext(Dispatchers.IO) {
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
