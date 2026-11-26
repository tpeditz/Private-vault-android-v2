package com.tpeditz.privatevault.v2.ui

import com.tpeditz.privatevault.v2.data.CorruptedVaultRecordsException
import org.json.JSONException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Presentation-layer error mapping so ordinary users never see raw OpenSSL / cipher /
 * Kotlin exception text, internal paths, keys, or stack details.
 */
object VaultErrorMapper {

    enum class RestoreErrorKind {
        AUTH_OR_DAMAGED_PASSWORD,
        UNSUPPORTED_FORMAT,
        INCOMPLETE_OR_DAMAGED,
        MISSING_REFERENCED_ASSETS,
        INSUFFICIENT_STORAGE,
        FILE_UNACCESSIBLE,
        CANCELLED,
        VAULT_LOCKED,
        UNEXPECTED
    }

    data class FriendlyRestoreError(
        val kind: RestoreErrorKind,
        val title: String,
        val message: String,
        val canRetryPassword: Boolean = false,
        val canChooseAnotherFile: Boolean = false
    )

    fun mapRestoreError(throwable: Throwable?): FriendlyRestoreError {
        if (throwable is CancellationException) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.CANCELLED,
                title = "Restore cancelled",
                message = "Restore cancelled."
            )
        }

        val chainMessages = buildString {
            var cur = throwable
            var depth = 0
            while (cur != null && depth < 6) {
                append(cur.javaClass.name).append(' ').append(cur.message.orEmpty()).append('\n')
                cur = cur.cause
                depth++
            }
        }.lowercase()

        if (throwable is AEADBadTagException ||
            throwable is BadPaddingException ||
            throwable is GeneralSecurityException ||
            chainMessages.contains("aeadbadtag") ||
            chainMessages.contains("bad decrypt") ||
            chainMessages.contains("bad_decrypt") ||
            chainMessages.contains("mac check") ||
            chainMessages.contains("tag mismatch") ||
            chainMessages.contains("wrong password")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.AUTH_OR_DAMAGED_PASSWORD,
                title = "Unable to restore backup",
                message = "The backup password is incorrect, or the backup file is damaged. Check your backup password and try again.",
                canRetryPassword = true,
                canChooseAnotherFile = true
            )
        }

        if (chainMessages.contains("vault is locked") ||
            chainMessages.contains("unlocked session") ||
            chainMessages.contains("session changed") ||
            chainMessages.contains("session invalidated") ||
            chainMessages.contains("wiped")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.VAULT_LOCKED,
                title = "Vault locked",
                message = "Your vault was locked. Unlock it and try again."
            )
        }

        if (chainMessages.contains("unrecognized backup file header") ||
            chainMessages.contains("unrecognized file header") ||
            chainMessages.contains("unsupported backup schema") ||
            chainMessages.contains("unsupported legacy backup")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.UNSUPPORTED_FORMAT,
                title = "Unsupported backup file",
                message = "This backup format is not supported.",
                canChooseAnotherFile = true
            )
        }

        if (chainMessages.contains("insufficient device storage") ||
            chainMessages.contains("no space left") ||
            chainMessages.contains("enospc") ||
            chainMessages.contains("exceeds maximum supported size")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.INSUFFICIENT_STORAGE,
                title = "Not enough storage",
                message = "Not enough storage space to restore this backup."
            )
        }

        if (chainMessages.contains("missing media payload") ||
            chainMessages.contains("missing encrypted") ||
            chainMessages.contains("missing required") ||
            chainMessages.contains("missing thumbnail") ||
            chainMessages.contains("missing document")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.MISSING_REFERENCED_ASSETS,
                title = "Incomplete backup",
                message = "Some required backup files are missing. Your existing vault has not been changed.",
                canChooseAnotherFile = true
            )
        }

        if (throwable is FileNotFoundException ||
            throwable is SecurityException ||
            chainMessages.contains("couldn't open") ||
            chainMessages.contains("could not open") ||
            chainMessages.contains("permission denial")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.FILE_UNACCESSIBLE,
                title = "Unable to open file",
                message = "Private Vault couldn't open this file. Choose the backup again.",
                canChooseAnotherFile = true
            )
        }

        if (throwable is EOFException ||
            throwable is JSONException ||
            chainMessages.contains("truncated") ||
            chainMessages.contains("sha-256 mismatch") ||
            chainMessages.contains("payload integrity") ||
            chainMessages.contains("corrupt") ||
            chainMessages.contains("invalid manifest") ||
            chainMessages.contains("invalid header") ||
            chainMessages.contains("pvm1") ||
            chainMessages.contains("pvt1") ||
            chainMessages.contains("blank id") ||
            chainMessages.contains("duplicate record")
        ) {
            return FriendlyRestoreError(
                kind = RestoreErrorKind.INCOMPLETE_OR_DAMAGED,
                title = "Damaged backup file",
                message = "This backup is incomplete or damaged.",
                canChooseAnotherFile = true
            )
        }

        return FriendlyRestoreError(
            kind = RestoreErrorKind.UNEXPECTED,
            title = "Unable to restore backup",
            message = "Something went wrong while reading this backup. Check the file and try again.",
            canRetryPassword = false,
            canChooseAnotherFile = true
        )
    }

    fun mapBackupExportError(throwable: Throwable?): String {
        if (throwable is CancellationException) return "Backup cancelled."
        if (throwable is CorruptedVaultRecordsException) {
            return "Some vault items could not be read. Your previous backup has been kept."
        }
        if (throwable is GeneralSecurityException) {
            return "Some vault files could not be verified for this backup. Your previous backup has been kept."
        }
        val msg = throwable?.message.orEmpty().lowercase()
        return when {
            msg.contains("vault is locked") || msg.contains("session") || msg.contains("wiped") ->
                "Your vault was locked. Unlock it and try again."
            msg.contains("some vault items could not be read") ->
                "Some vault items could not be read. Your previous backup has been kept."
            msg.contains("at least 12 characters") || msg.contains("characters") ->
                throwable?.message ?: "Please choose a stronger backup password."
            msg.contains("2 gib") || msg.contains("exceeds maximum") || msg.contains("no space") || msg.contains("enospc") ->
                "Not enough storage to complete the backup."
            msg.contains("missing encrypted") || msg.contains("not found") || msg.contains("integrity") || msg.contains("sha-256") ->
                "Some vault files could not be verified for this backup. Your previous backup has been kept."
            else -> "Backup could not be completed. Your previous backup has been kept."
        }
    }

    fun mapCloudError(throwable: Throwable?, httpCode: Int? = null): String {
        if (httpCode == 401 || httpCode == 403) {
            return "Reconnect your Google account to continue."
        }
        val msg = throwable?.message.orEmpty().lowercase()
        return when {
            msg.contains("permission") || msg.contains("revoked") || msg.contains("not connected") || msg.contains("authorization") ->
                "Reconnect your Google account to continue."
            throwable is UnknownHostException || throwable is SocketTimeoutException || msg.contains("network") || msg.contains("connect") ->
                "Check your internet connection and try again."
            else -> "Could not sync with Google Drive right now. Please try again."
        }
    }

    fun mapFileExportError(throwable: Throwable?): String {
        val msg = throwable?.message.orEmpty().lowercase()
        return when {
            throwable is FileNotFoundException || throwable is SecurityException ->
                "Private Vault couldn't write to the chosen location. Try another folder."
            msg.contains("no space") || msg.contains("enospc") ->
                "Not enough storage space to export this file."
            throwable is IOException -> "Could not finish exporting file. Check available storage and try again."
            else -> "Could not export file. Please try again."
        }
    }

    fun mapVaultActionError(throwable: Throwable?): String {
        val msg = throwable?.message.orEmpty().lowercase()
        return when {
            msg.contains("vault is locked") || msg.contains("session") ->
                "Your vault was locked. Unlock it and try again."
            msg.contains("at least 12 characters") ->
                throwable?.message ?: "Use at least 12 characters for your new password."
            throwable is GeneralSecurityException ->
                "Could not verify encryption credentials. Please try again."
            else -> "Operation could not be completed. Please try again."
        }
    }
}
