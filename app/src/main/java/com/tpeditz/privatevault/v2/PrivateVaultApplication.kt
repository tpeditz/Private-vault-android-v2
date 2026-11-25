package com.tpeditz.privatevault.v2

import android.app.Application
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.data.PrivateVaultDatabase
import com.tpeditz.privatevault.v2.data.VaultRepository
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.security.SensitiveClipboardManager
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import com.tpeditz.privatevault.v2.sync.GoogleDriveSyncManager

/**
 * Application container for PRIVATE VAULT V2.
 * Maintains central, thread-safe instances of database, repository, session authority,
 * preferences, and system service managers.
 */
class PrivateVaultApplication : Application() {

    lateinit var database: PrivateVaultDatabase
        private set

    lateinit var sessionManager: VaultSessionManager
        private set

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var clipboardManager: SensitiveClipboardManager
        private set

    lateinit var repository: VaultRepository
        private set

    lateinit var otaUpdateManager: OtaUpdateManager
        private set

    lateinit var googleDriveSyncManager: GoogleDriveSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        sessionManager = VaultSessionManager()
        database = PrivateVaultDatabase.getInstance(this)
        preferencesRepository = AppPreferencesRepository(this)
        clipboardManager = SensitiveClipboardManager(this)

        repository = VaultRepository(
            context = this,
            dao = database.vaultDao(),
            sessionManager = sessionManager,
            preferencesRepository = preferencesRepository
        )

        otaUpdateManager = OtaUpdateManager(this)
        googleDriveSyncManager = GoogleDriveSyncManager()
    }

    companion object {
        lateinit var instance: PrivateVaultApplication
            private set
    }
}
