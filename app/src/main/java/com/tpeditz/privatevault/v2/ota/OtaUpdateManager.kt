package com.tpeditz.privatevault.v2.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.security.SafeVaultLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Self-contained HTTPS OTA Update Manager for PRIVATE VAULT V2.
 * Connects to single official GitHub repository: tpeditz/Private-vault-android-v2.
 */
class OtaUpdateManager(
    private val context: Context?,
    private val installedSignerDigestsProvider: () -> Set<String> = {
        context?.let { getInstalledSignerSha256Digests(it) } ?: emptySet()
    },
    private val installedVersionCodeProvider: () -> Long = {
        context?.let { getInstalledVersionCode(it) } ?: 1L
    },
    private val archiveInfoParser: (Context?, File) -> PackageInfo? = { ctx, apkFile ->
        ctx?.packageManager?.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_META_DATA
        )
    },
    // Keep the verified candidate in private files, not evictable cache.
    private val cacheDirProvider: () -> File = { requireNotNull(context).filesDir },
    private val canRequestPackageInstallsProvider: () -> Boolean = {
        val ctx = context
        if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    },
    private val allowedHosts: Set<String> = ALLOWED_DOWNLOAD_REDIRECT_HOSTS,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {

    private val _otaState = MutableStateFlow<OtaState>(OtaState.Idle)
    val otaState: StateFlow<OtaState> = _otaState.asStateFlow()

    private val operationMutex = Mutex()
    private val pendingStore: PendingOtaDownloadStore
        get() = PendingOtaDownloadStore(File(cacheDirProvider(), "ota_updates"))

    @Volatile
    private var activeDownloadCall: okhttp3.Call? = null

    @Volatile
    private var isDownloadCancelled: Boolean = false

    companion object {
        const val OFFICIAL_REPO = "tpeditz/Private-vault-android-v2"
        const val OFFICIAL_APPLICATION_ID = "com.tpeditz.privatevault.v2"
        const val EXPECTED_RELEASE_SIGNER_CERT_SHA256 =
            "3b:ee:ff:13:8b:71:61:68:b2:f1:66:98:3d:42:49:6b:9d:24:f6:29:b7:5e:e2:5b:da:fd:16:67:6f:06:a7:70"

        private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L // 1 MiB
        private const val MAX_APK_DOWNLOAD_BYTES = 250L * 1024L * 1024L // 250 MiB

        private val ALLOWED_DOWNLOAD_REDIRECT_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        fun getInstalledVersionCode(context: Context): Long {
            return try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {
                1L
            }
        }

        fun getInstalledSignerSha256Digests(context: Context): Set<String> {
            val results = HashSet<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    val signingInfo = pInfo.signingInfo ?: return results
                    val signatures = if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    signatures?.forEach { sig ->
                        results.add(sha256Hex(sig.toByteArray()).lowercase(Locale.US))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNATURES
                    )
                    @Suppress("DEPRECATION")
                    pInfo.signatures?.forEach { sig ->
                        results.add(sha256Hex(sig.toByteArray()).lowercase(Locale.US))
                    }
                }
            } catch (_: Exception) {
            }
            return results
        }

        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun checkForUpdates(
        manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
        isUserInitiated: Boolean = true
    ): OtaState = withContext(Dispatchers.IO) {
        if (!operationMutex.tryLock()) return@withContext _otaState.value
        try {
            val previous = _otaState.value
            // Checking must never discard a verified candidate or interrupt a download.
            if (previous.isPendingInstallation || previous is OtaState.Downloading ||
                previous is OtaState.Verifying || previous is OtaState.Checking) {
                return@withContext previous
            }
            _otaState.value = OtaState.Checking

            try {
                validateManifestUrl(manifestUrl)
                val request = Request.Builder().url(manifestUrl).get().build()
                val manifestJsonString = executeWithRedirects(request, maxHops = 3)
                val manifest = parseAndValidateManifest(manifestJsonString)

                val currentVersionCode = installedVersionCodeProvider()
                val currentVersionName = BuildConfig.VERSION_NAME

                if (manifest.versionCode > currentVersionCode) {
                    val showPrompt = !isUserInitiated && (
                        previous !is OtaState.UpdateAvailable ||
                            previous.manifest.versionCode != manifest.versionCode ||
                            previous.showPromptDialog
                        )
                    val state = OtaState.UpdateAvailable(manifest = manifest, showPromptDialog = showPrompt)
                    _otaState.value = state
                    state
                } else {
                    val state = OtaState.UpToDate(currentVersionName, currentVersionCode)
                    _otaState.value = state
                    state
                }
            } catch (e: CancellationException) {
                _otaState.value = OtaState.Idle
                throw e
            } catch (e: HttpStatusException) {
                SafeVaultLogger.w("OTA", "HTTP ${e.code} while checking updates")
                val state = if (e.code == 404) {
                    OtaState.Error(
                        title = "No Release Published Yet",
                        detail = "No public release was found on GitHub yet. You are running Private Vault ${BuildConfig.VERSION_NAME}."
                    )
                } else {
                    OtaState.Error(
                        title = "Update Check Failed",
                        detail = "Server returned status ${e.code}. Please try again later."
                    )
                }
                _otaState.value = state
                state
            } catch (e: java.io.IOException) {
                SafeVaultLogger.w("OTA", "Network error during update check: ${e.message}")
                val state = OtaState.Error(
                    title = "Connection Error",
                    detail = "Could not connect to GitHub. Check your internet connection and try again."
                )
                _otaState.value = state
                state
            } catch (e: Exception) {
                SafeVaultLogger.w("OTA", "Check failed: ${e.message}")
                val state = OtaState.Error(
                    title = "Update Check Failed",
                    detail = e.message ?: "Could not check for updates. Check your connection."
                )
                _otaState.value = state
                state
            }
        } finally {
            operationMutex.unlock()
        }
    }

    fun dismissUpdatePrompt() {
        val current = _otaState.value
        if (current is OtaState.UpdateAvailable) {
            _otaState.compareAndSet(current, current.copy(showPromptDialog = false))
        }
    }

    suspend fun startDownload(manifest: UpdateManifest): Unit = withContext(Dispatchers.IO) {
        operationMutex.lock()
        try {
            if (_otaState.value.isPendingInstallation) return@withContext
            isDownloadCancelled = false
            _otaState.value = OtaState.Downloading(manifest, 0L, manifest.sizeBytes)

            val store = pendingStore
            store.clear()
            val targetApk = store.apkFile(manifest.versionCode)
            targetApk.parentFile?.mkdirs()
            var currentUrl = manifest.apkUrl

            try {
                var hopCount = 0
                var finalResponse: Response? = null

                while (hopCount < 5) {
                    validateDownloadHopUrl(currentUrl)
                    val request = Request.Builder().url(currentUrl).get().build()
                    val call = httpClient.newCall(request)
                    activeDownloadCall = call
                    val response = call.execute()

                    if (response.isRedirect) {
                        val location = response.header("Location")
                        response.close()
                        require(!location.isNullOrBlank()) { "Redirect missing Location header." }
                        currentUrl = resolveRedirect(currentUrl, location)
                        hopCount++
                        continue
                    }

                    if (!response.isSuccessful) {
                        response.close()
                        throw IllegalArgumentException("HTTP download failed with code ${response.code}.")
                    }
                    finalResponse = response
                    break
                }

                val response = finalResponse ?: throw IllegalArgumentException("Too many redirects.")
                val body = response.body ?: throw IllegalArgumentException("Response body is empty.")
                val declaredLen = body.contentLength()
                require(declaredLen <= MAX_APK_DOWNLOAD_BYTES) {
                    "Update package exceeds maximum supported size limit."
                }
                val totalBytes = if (declaredLen > 0) declaredLen else manifest.sizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(targetApk).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesDownloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isDownloadCancelled) {
                                targetApk.delete()
                                return@withContext
                            }
                            bytesDownloaded += read.toLong()
                            require(bytesDownloaded <= MAX_APK_DOWNLOAD_BYTES) {
                                "Update package exceeded maximum size limit during download."
                            }
                            output.write(buffer, 0, read)
                            _otaState.value = OtaState.Downloading(manifest, bytesDownloaded, totalBytes)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                _otaState.value = OtaState.Verifying(manifest)
                val certSha256 = verifyCandidateApkFile(targetApk, manifest)

                if (isDownloadCancelled) {
                    store.clear()
                    return@withContext
                }
                // Commit metadata only after cryptographic verification succeeds.
                store.save(manifest)
                if (isDownloadCancelled) {
                    store.clear()
                    return@withContext
                }
                _otaState.value = pendingInstallState(manifest, targetApk, certSha256, canRequestPackageInstallsProvider())
            } catch (e: CancellationException) {
                store.clear()
                _otaState.value = OtaState.Idle
                throw e
            } catch (e: java.io.IOException) {
                store.clear()
                if (isDownloadCancelled) return@withContext
                SafeVaultLogger.e("OTA", "Download/verify I/O error", e)
                _otaState.value = OtaState.Error(
                    title = "Download Interrupted",
                    detail = "Could not download the update. Check your internet connection and try again.",
                    recoverableManifest = manifest
                )
            } catch (e: Exception) {
                store.clear()
                if (isDownloadCancelled) return@withContext
                SafeVaultLogger.e("OTA", "Download/verify error", e)
                val friendlyDetail = when {
                    e is IllegalArgumentException && e.message?.contains("APK") == true ->
                        "The downloaded update failed security verification and was removed."
                    e is IllegalArgumentException && e.message?.contains("host", ignoreCase = true) == true ->
                        "Update download host could not be verified."
                    else -> "Could not prepare update installation. Check your connection and try again."
                }
                _otaState.value = OtaState.Error(
                    title = "Update Could Not Be Verified",
                    detail = friendlyDetail,
                    recoverableManifest = manifest
                )
            } finally {
                activeDownloadCall = null
            }
        } finally {
            operationMutex.unlock()
        }
    }

    // Called from the activity foreground and the Settings activity-result callback.
    // Also recovers a verified download after Android kills/recreates the process.
    suspend fun recheckInstallPermissionAndVerifyCandidate(): OtaState = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val current = _otaState.value
            if (!current.isPendingInstallation && current !is OtaState.Idle) return@withLock current
            val store = pendingStore
            var manifest: UpdateManifest? = null
            try {
                val saved = store.readManifestJson()
                if (saved == null) {
                    if (!current.isPendingInstallation) {
                        store.clear() // clean up any interrupted, unverified download
                        return@withLock current
                    }
                    manifest = current.pendingManifest
                    throw IllegalStateException("Downloaded update is missing its metadata")
                }
                val parsed = parseAndValidateManifest(saved)
                manifest = parsed
                if (parsed.versionCode <= installedVersionCodeProvider()) {
                    store.clear()
                    _otaState.value = OtaState.Idle
                    return@withLock OtaState.Idle
                }
                val apk = store.apkFile(parsed.versionCode)
                val cert = verifyCandidateApkFile(apk, parsed)
                if (isDownloadCancelled) {
                    store.clear()
                    _otaState.value = OtaState.Idle
                    return@withLock OtaState.Idle
                }
                val next = pendingInstallState(parsed, apk, cert, canRequestPackageInstallsProvider())
                _otaState.value = next
                next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.clear()
                if (isDownloadCancelled) return@withLock OtaState.Idle
                SafeVaultLogger.w("OTA", "Pending candidate re-verification failed")
                val error = OtaState.Error(
                    title = "Update Needs Re-Download",
                    detail = "The saved update is missing or could not be verified. Download it again to continue.",
                    recoverableManifest = manifest
                )
                _otaState.value = error
                error
            }
        }
    }

    fun cancelActiveDownload() {
        isDownloadCancelled = true
        activeDownloadCall?.cancel()
        activeDownloadCall = null
        // An active operation performs its cleanup before releasing the mutex.
        if (operationMutex.tryLock()) {
            try {
                pendingStore.clear()
                _otaState.value = OtaState.Idle
            } finally {
                operationMutex.unlock()
            }
        } else if (_otaState.value.isPendingInstallation || _otaState.value is OtaState.Downloading ||
            _otaState.value is OtaState.Verifying) {
            _otaState.value = OtaState.Idle
        }
    }

    suspend fun launchPackageInstaller(apkFile: File) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val ctx = context ?: return@withLock
            val current = _otaState.value
            val manifest = current.pendingManifest ?: return@withLock
            val store = pendingStore
            // Never open an arbitrary file or an installer without a current verified candidate.
            val expectedApk = store.apkFile(manifest.versionCode)
            if (expectedApk != apkFile) return@withLock
            try {
                val cert = verifyCandidateApkFile(expectedApk, manifest)
                if (isDownloadCancelled) {
                    store.clear()
                    return@withLock
                }
                if (!canRequestPackageInstallsProvider()) {
                    _otaState.value = pendingInstallState(manifest, expectedApk, cert, false)
                    return@withLock
                }
                _otaState.value = pendingInstallState(manifest, expectedApk, cert, true)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", expectedApk)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Android's installer still requires explicit user confirmation.
                withContext(Dispatchers.Main) main@{
                    if (isDownloadCancelled) {
                        _otaState.value = OtaState.Idle
                        return@main
                    }
                    runCatching { ctx.startActivity(installIntent) }.onFailure {
                        // Keep the candidate available for another installation attempt.
                        _otaState.value = pendingInstallState(manifest, expectedApk, cert, canRequestPackageInstallsProvider())
                    }
                }
                if (isDownloadCancelled) store.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.clear()
                _otaState.value = OtaState.Error(
                    title = "Update Could Not Be Verified",
                    detail = "Verification failed before install. Download the update again.",
                    recoverableManifest = manifest
                )
            }
        }
    }

    fun unknownSourcesSettingsIntent(): Intent? {
        val ctx = context ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        } else null
    }

    fun verifyCandidateApkFile(apkFile: File, manifest: UpdateManifest): String {
        require(apkFile.exists() && apkFile.isFile) { "Candidate APK file does not exist." }
        if (manifest.sizeBytes > 0L) {
            require(apkFile.length() == manifest.sizeBytes) {
                "APK byte size mismatch. Expected ${manifest.sizeBytes}, got ${apkFile.length()}."
            }
        }

        // SHA-256 Digest verification
        val digest = sha256OfFile(apkFile)
        require(digest.equals(manifest.sha256, ignoreCase = true)) {
            "APK SHA-256 digest mismatch."
        }

        // Verify cryptographic signature via ApkVerifier
        val verifier = ApkVerifier.Builder(apkFile).build()
        val result = verifier.verify()
        require(result.isVerified) { "APK signature verification failed." }

        val signerCerts = result.signerCertificates
        require(signerCerts.isNotEmpty()) { "No signer certificates found in candidate APK." }

        val candidateCertSha256List = signerCerts.map { cert ->
            sha256Hex(cert.encoded).lowercase(Locale.US)
        }

        // Must match either installed app's signer or official permanent release signer certificate
        val installedSigners = installedSignerDigestsProvider()
        val normalizedExpected = EXPECTED_RELEASE_SIGNER_CERT_SHA256.replace(":", "").lowercase(Locale.US)
        val normalizedManifestCert = manifest.signingCertSha256.replace(":", "").lowercase(Locale.US)

        val matchesInstalled = candidateCertSha256List.any { installedSigners.contains(it) }
        val matchesExpectedRelease = candidateCertSha256List.any { it.replace(":", "") == normalizedExpected }
        val matchesManifestCert = candidateCertSha256List.any { it.replace(":", "") == normalizedManifestCert }

        require((matchesInstalled || matchesExpectedRelease) && matchesManifestCert) {
            "APK signing certificate does not match trusted Private Vault signing certificate."
        }

        // Package metadata verification
        val pInfo = archiveInfoParser(context, apkFile)
            ?: throw IllegalArgumentException("Could not parse package metadata from candidate APK.")

        require(pInfo.packageName == OFFICIAL_APPLICATION_ID) {
            "Package ID mismatch: expected $OFFICIAL_APPLICATION_ID, got ${pInfo.packageName}."
        }
        val appInfo = pInfo.applicationInfo
        if (appInfo != null) {
            require((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                "Debuggable APK candidate rejected for production update."
            }
        }

        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
        require(apkVersionCode == manifest.versionCode.toLong()) {
            "Candidate APK versionCode ($apkVersionCode) does not match manifest versionCode (${manifest.versionCode})."
        }
        if (!pInfo.versionName.isNullOrBlank()) {
            require(pInfo.versionName == manifest.versionName) {
                "Candidate APK versionName (${pInfo.versionName}) does not match manifest (${manifest.versionName})."
            }
        }
        val currentVersionCode = installedVersionCodeProvider()
        require(apkVersionCode > currentVersionCode) {
            "Downgrade or duplicate versionCode rejected ($apkVersionCode <= $currentVersionCode)."
        }

        return candidateCertSha256List.first()
    }

    private fun sha256OfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun parseAndValidateManifest(jsonString: String): UpdateManifest {
        val root = JSONObject(jsonString)
        val schemaVer = root.optInt("schemaVersion", -1)
        require(schemaVer == 1) { "Unsupported manifest schema version: $schemaVer" }

        val appId = root.getString("applicationId")
        require(appId == OFFICIAL_APPLICATION_ID) { "Manifest applicationId mismatch: $appId" }

        val vCode = root.getInt("versionCode")
        require(vCode > 0) { "Invalid manifest versionCode: $vCode" }
        val vName = root.getString("versionName").trim()
        require(vName.isNotBlank()) { "Manifest versionName cannot be empty." }

        val apkUrl = root.getString("apkUrl").trim()
        validateDownloadHopUrl(apkUrl)

        val sha256 = root.getString("sha256").trim().lowercase(Locale.US)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid SHA-256 checksum format."
        }

        val sizeBytes = root.optLong("sizeBytes", 0L)
        require(sizeBytes >= 0L && sizeBytes <= MAX_APK_DOWNLOAD_BYTES) {
            "Update package exceeds maximum supported size limit."
        }
        val publishedAt = root.optString("publishedAt", "")
        val notes = root.optString("releaseNotes", "")
        val certSha256 = root.getString("signingCertSha256").trim()
        val normalizedManifestCert = certSha256.replace(":", "").lowercase(Locale.US)
        val normalizedExpectedCert = EXPECTED_RELEASE_SIGNER_CERT_SHA256.replace(":", "").lowercase(Locale.US)
        require(normalizedManifestCert == normalizedExpectedCert) {
            "Manifest signingCertSha256 does not match Private Vault official release certificate."
        }

        return UpdateManifest(
            schemaVersion = schemaVer,
            applicationId = appId,
            versionCode = vCode,
            versionName = vName,
            apkUrl = apkUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            publishedAt = publishedAt,
            releaseNotes = notes,
            signingCertSha256 = certSha256
        )
    }

    private fun validateManifestUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Manifest URL must use HTTPS." }
        require(uri.userInfo == null) { "Credentials in manifest URL rejected." }
        val host = uri.host?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Missing host in manifest URL.")
        require(allowedHosts.contains(host)) { "Untrusted manifest host rejected: $host" }
    }

    private fun validateDownloadHopUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Download URL must use HTTPS." }
        require(uri.userInfo == null) { "Credentials in download URL rejected." }
        val host = uri.host?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Missing host in download URL.")
        require(allowedHosts.contains(host)) {
            "Untrusted download host rejected: $host"
        }
    }

    private fun resolveRedirect(base: String, location: String): String {
        return URI(base).resolve(location).toString()
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP status $code")

    private fun executeWithRedirects(request: Request, maxHops: Int): String {
        var currentReq = request
        var hops = 0
        while (hops <= maxHops) {
            val response = httpClient.newCall(currentReq).execute()
            if (response.isRedirect) {
                val loc = response.header("Location")
                response.close()
                require(!loc.isNullOrBlank()) { "Redirect without Location header." }
                val targetUrl = resolveRedirect(currentReq.url.toString(), loc)
                validateDownloadHopUrl(targetUrl)
                currentReq = Request.Builder().url(targetUrl).get().build()
                hops++
                continue
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw HttpStatusException(code)
            }
            return response.use { it.body?.string().orEmpty() }
        }
        throw IllegalArgumentException("Too many redirects.")
    }
}
