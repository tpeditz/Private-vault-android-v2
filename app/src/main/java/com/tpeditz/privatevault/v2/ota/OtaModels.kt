package com.tpeditz.privatevault.v2.ota

import java.io.File

data class UpdateManifest(
    val schemaVersion: Int = 1,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val releaseNotes: String,
    val signingCertSha256: String
)

sealed interface OtaState {
    data object Idle : OtaState
    data object Checking : OtaState
    data class UpToDate(val currentVersionName: String, val currentVersionCode: Long) : OtaState
    data class UpdateAvailable(
        val manifest: UpdateManifest,
        val showPromptDialog: Boolean = true
    ) : OtaState
    data class Downloading(
        val manifest: UpdateManifest,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : OtaState
    data class Verifying(val manifest: UpdateManifest) : OtaState
    data class ReadyToInstall(
        val manifest: UpdateManifest,
        val verifiedApkFile: File,
        val signerCertificateSha256: String
    ) : OtaState
    data class NeedsUnknownSourcesPermission(
        val manifest: UpdateManifest,
        val verifiedApkFile: File,
        val signerCertificateSha256: String
    ) : OtaState
    data class Error(
        val title: String,
        val detail: String,
        val recoverableManifest: UpdateManifest? = null
    ) : OtaState
}

internal val OtaState.pendingManifest: UpdateManifest?
    get() = when (this) {
        is OtaState.ReadyToInstall -> manifest
        is OtaState.NeedsUnknownSourcesPermission -> manifest
        else -> null
    }

internal val OtaState.isPendingInstallation: Boolean
    get() = pendingManifest != null

/** Called only after the candidate has passed actual APK verification. */
internal fun pendingInstallState(
    manifest: UpdateManifest,
    apk: File,
    verifiedCertificate: String,
    permissionGranted: Boolean
): OtaState = if (permissionGranted) {
    OtaState.ReadyToInstall(manifest, apk, verifiedCertificate)
} else {
    OtaState.NeedsUnknownSourcesPermission(manifest, apk, verifiedCertificate)
}
