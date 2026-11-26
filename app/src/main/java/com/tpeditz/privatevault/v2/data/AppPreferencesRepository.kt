package com.tpeditz.privatevault.v2.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class CloudBackupFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}

internal val Context.dataStore by preferencesDataStore(name = "private_vault_prefs")

class AppPreferencesRepository(private val context: Context) {

    companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_AUTO_LOCK_TIMEOUT = intPreferencesKey("auto_lock_timeout_seconds")
        val KEY_LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val KEY_SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        val KEY_CLIPBOARD_TTL = intPreferencesKey("clipboard_ttl_seconds")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_BIOMETRIC_KEY_ALIAS = stringPreferencesKey("biometric_key_alias")
        val KEY_BIOMETRIC_PENDING_ALIAS = stringPreferencesKey("biometric_pending_alias")
        val KEY_BIOMETRIC_PENDING_NONCE_SHA256 = stringPreferencesKey("biometric_pending_nonce_sha256")
        val KEY_AUTO_CLOUD_BACKUP = booleanPreferencesKey("auto_cloud_backup")
        val KEY_CLOUD_BACKUP_FREQ = stringPreferencesKey("cloud_backup_frequency")
        val KEY_CLOUD_BACKUP_WIFI = booleanPreferencesKey("cloud_backup_wifi_only")
        val KEY_LAST_LOCAL_BACKUP = longPreferencesKey("last_local_backup_epoch_ms")
        val KEY_LAST_CLOUD_BACKUP = longPreferencesKey("last_cloud_backup_epoch_ms")
        val KEY_CONNECTED_GOOGLE_ACCOUNT = stringPreferencesKey("connected_google_account")
        val KEY_AUTO_CLOUD_BACKUP_STATUS = stringPreferencesKey("auto_cloud_backup_status")
        val KEY_AUTO_OTA_CHECK = booleanPreferencesKey("automatic_ota_check_enabled")
        val KEY_LAST_OTA_CHECK = longPreferencesKey("last_ota_check_epoch_ms")
    }

    val themePreference: Flow<ThemePreference> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_THEME] ?: ThemePreference.SYSTEM.name
        runCatching { ThemePreference.valueOf(raw) }.getOrDefault(ThemePreference.SYSTEM)
    }

    val autoLockTimeoutSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_LOCK_TIMEOUT] ?: 60 // Default 60 seconds
    }

    val lockOnScreenOff: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOCK_ON_SCREEN_OFF] ?: true
    }

    val screenshotProtectionEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SCREENSHOT_PROTECTION] ?: true
    }

    val clipboardTtlSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLIPBOARD_TTL] ?: 30
    }

    val biometricUnlockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRIC_ENABLED] ?: false
    }

    val biometricKeyAlias: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRIC_KEY_ALIAS]?.takeIf { it.isNotBlank() }
    }

    val biometricPendingAlias: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRIC_PENDING_ALIAS]?.takeIf { it.isNotBlank() }
    }

    val biometricPendingNonceSha256: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRIC_PENDING_NONCE_SHA256]?.takeIf { it.isNotBlank() }
    }

    val autoCloudBackupEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_CLOUD_BACKUP] ?: false
    }

    val cloudBackupFrequency: Flow<CloudBackupFrequency> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_CLOUD_BACKUP_FREQ] ?: CloudBackupFrequency.WEEKLY.name
        runCatching { CloudBackupFrequency.valueOf(raw) }.getOrDefault(CloudBackupFrequency.WEEKLY)
    }

    val cloudBackupWifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_BACKUP_WIFI] ?: true
    }

    val lastLocalBackupEpochMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_LOCAL_BACKUP] ?: 0L
    }

    val lastCloudBackupEpochMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CLOUD_BACKUP] ?: 0L
    }

    val connectedGoogleAccount: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONNECTED_GOOGLE_ACCOUNT].orEmpty()
    }

    val autoCloudBackupStatus: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_CLOUD_BACKUP_STATUS].orEmpty()
    }

    val automaticOtaCheckEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_OTA_CHECK] ?: true
    }

    val lastOtaCheckEpochMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_OTA_CHECK] ?: 0L
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.dataStore.edit { it[KEY_THEME] = theme.name }
    }

    suspend fun setAutoLockTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_AUTO_LOCK_TIMEOUT] = seconds }
    }

    suspend fun setLockOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LOCK_ON_SCREEN_OFF] = enabled }
    }

    suspend fun setScreenshotProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SCREENSHOT_PROTECTION] = enabled }
    }

    suspend fun setClipboardTtlSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_CLIPBOARD_TTL] = seconds }
    }

    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setBiometricKeyAlias(alias: String?) {
        context.dataStore.edit { prefs ->
            if (alias.isNullOrBlank()) prefs.remove(KEY_BIOMETRIC_KEY_ALIAS)
            else prefs[KEY_BIOMETRIC_KEY_ALIAS] = alias
        }
    }

    suspend fun stagePendingBiometricCommit(alias: String, nonceSha256Hex: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_PENDING_ALIAS] = alias
            prefs[KEY_BIOMETRIC_PENDING_NONCE_SHA256] = nonceSha256Hex
        }
    }

    suspend fun commitPendingBiometricAlias(alias: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_KEY_ALIAS] = alias
            prefs[KEY_BIOMETRIC_ENABLED] = true
            prefs.remove(KEY_BIOMETRIC_PENDING_ALIAS)
            prefs.remove(KEY_BIOMETRIC_PENDING_NONCE_SHA256)
        }
    }

    suspend fun clearPendingBiometricCommit() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_BIOMETRIC_PENDING_ALIAS)
            prefs.remove(KEY_BIOMETRIC_PENDING_NONCE_SHA256)
        }
    }

    suspend fun clearBiometricPreferences() {
        context.dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_ENABLED] = false
            prefs.remove(KEY_BIOMETRIC_KEY_ALIAS)
            prefs.remove(KEY_BIOMETRIC_PENDING_ALIAS)
            prefs.remove(KEY_BIOMETRIC_PENDING_NONCE_SHA256)
        }
    }

    suspend fun setAutoCloudBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CLOUD_BACKUP] = enabled }
    }

    suspend fun setCloudBackupFrequency(freq: CloudBackupFrequency) {
        context.dataStore.edit { it[KEY_CLOUD_BACKUP_FREQ] = freq.name }
    }

    suspend fun setCloudBackupWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { it[KEY_CLOUD_BACKUP_WIFI] = wifiOnly }
    }

    suspend fun recordLocalBackup(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[KEY_LAST_LOCAL_BACKUP] = timestamp }
    }

    suspend fun recordCloudBackup(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[KEY_LAST_CLOUD_BACKUP] = timestamp }
    }

    suspend fun setConnectedGoogleAccount(email: String) {
        context.dataStore.edit { it[KEY_CONNECTED_GOOGLE_ACCOUNT] = email }
    }

    suspend fun setAutoCloudBackupStatus(status: String) {
        context.dataStore.edit { it[KEY_AUTO_CLOUD_BACKUP_STATUS] = status }
    }

    suspend fun setAutomaticOtaCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_OTA_CHECK] = enabled }
    }

    suspend fun setLastOtaCheckEpochMs(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[KEY_LAST_OTA_CHECK] = timestamp }
    }
}
