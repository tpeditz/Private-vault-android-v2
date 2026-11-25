package com.tpeditz.privatevault.v2.ota

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Only public release metadata and the downloaded APK are stored here.
 * The metadata is not a trust decision: recovery always re-verifies the APK.
 */
internal class PendingOtaDownloadStore(private val directory: File) {
    private val metadata = File(directory, "pending-update.json")

    fun apkFile(versionCode: Int): File {
        require(versionCode > 0)
        return File(directory, "private-vault-v2-$versionCode.apk")
    }

    fun readManifestJson(): String? {
        if (!metadata.isFile) return null
        require(metadata.length() in 1..1048576L) { "Invalid pending metadata length" }
        return metadata.readText(Charsets.UTF_8)
    }

    fun save(manifest: UpdateManifest) {
        require(apkFile(manifest.versionCode).isFile)
        require(directory.isDirectory || directory.mkdirs())
        val bytes = JSONObject().apply {
            put("schemaVersion", manifest.schemaVersion)
            put("applicationId", manifest.applicationId)
            put("versionCode", manifest.versionCode)
            put("versionName", manifest.versionName)
            put("apkUrl", manifest.apkUrl)
            put("sha256", manifest.sha256)
            put("sizeBytes", manifest.sizeBytes)
            put("publishedAt", manifest.publishedAt)
            put("releaseNotes", manifest.releaseNotes)
            put("signingCertSha256", manifest.signingCertSha256)
        }.toString().toByteArray(Charsets.UTF_8)
        val temp = File(directory, "pending-update.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            require(temp.renameTo(metadata)) { "Could not persist verified update metadata" }
        } finally {
            temp.delete()
        }
    }

    fun clear() {
        // Never touch any other application files or vault data.
        directory.listFiles()?.forEach { it.delete() }
    }
}
