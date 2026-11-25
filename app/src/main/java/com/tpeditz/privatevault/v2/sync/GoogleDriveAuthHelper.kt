package com.tpeditz.privatevault.v2.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.tpeditz.privatevault.v2.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for Google Sign-In and OAuth2 Token resolution for PRIVATE VAULT V2.
 * Uses minimal `drive.appdata` scope and public `GOOGLE_WEB_CLIENT_ID` from BuildConfig.
 */
object GoogleDriveAuthHelper {

    fun isGoogleDriveConfigured(): Boolean {
        return BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com")
    }

    fun isPlayServicesAvailable(context: Context): Boolean {
        return runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    private fun getGoogleSignInOptions(): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(GoogleDriveSyncManager.SCOPE_DRIVE_APPDATA)
            )

        if (isGoogleDriveConfigured()) {
            builder.requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
        }
        return builder.build()
    }

    fun getClient(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context, getGoogleSignInOptions())
    }

    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        return runCatching { GoogleSignIn.getLastSignedInAccount(context) }.getOrNull()
    }

    fun hasPermissions(context: Context): Boolean {
        val account = getLastSignedInAccount(context) ?: return false
        val options = getGoogleSignInOptions()
        return runCatching { GoogleSignIn.hasPermissions(account, *options.scopeArray) }.getOrDefault(false)
    }

    fun parseSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                Result.success(account)
            } else {
                Result.failure(Exception("Google sign-in returned no account."))
            }
        } catch (e: ApiException) {
            val friendly = when (e.statusCode) {
                12501 -> "Google sign-in was cancelled."
                12502 -> "Sign-in is already in progress."
                7 -> "Network connection unavailable. Check your internet and try again."
                4 -> "Please sign in with your Google account to continue."
                10 -> "Google sign-in is not authorized for this app signature."
                else -> "Could not connect to Google (${e.statusCode})."
            }
            Result.failure(Exception(friendly, e))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Google sign-in failed.", e))
        }
    }

    suspend fun getAccessToken(context: Context, account: GoogleSignInAccount): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val androidAccount = account.account
                    ?: return@withContext Result.failure(Exception("Google account handle is unavailable. Please reconnect."))

                val scopeString = "oauth2:${GoogleDriveSyncManager.SCOPE_DRIVE_APPDATA}"
                val token = GoogleAuthUtil.getToken(context, androidAccount, scopeString)
                if (token.isNotBlank()) {
                    Result.success(token)
                } else {
                    Result.failure(Exception("Empty authorization token returned."))
                }
            } catch (e: Exception) {
                Result.failure(Exception("Google Drive permission check failed: ${e.message}", e))
            }
        }

    suspend fun refreshAccessToken(
        context: Context,
        account: GoogleSignInAccount,
        expiredToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, expiredToken) }
        getAccessToken(context, account)
    }

    suspend fun signOut(context: Context): Unit = withContext(Dispatchers.IO) {
        try {
            val client = getClient(context)
            runCatching { Tasks.await(client.signOut()) }
            runCatching { Tasks.await(client.revokeAccess()) }
        } catch (_: Exception) {
        }
    }
}
