package com.tpeditz.privatevault.v2.ui

import com.tpeditz.privatevault.v2.data.CredentialRecord
import com.tpeditz.privatevault.v2.data.InMemoryVaultDao
import com.tpeditz.privatevault.v2.data.VaultRepository
import com.tpeditz.privatevault.v2.security.PasswordGeneratorEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutofillRemovalAndPasswordManagerRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testManifestNavigationAndStateContainZeroAutofillComponents() {
        val manifestFile = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.exists() }
        val manifestText = manifestFile.readText()
        assertFalse(manifestText.contains("AutofillService", ignoreCase = true))
        assertFalse(manifestText.contains("AutofillAuthActivity", ignoreCase = true))
        assertFalse(manifestText.contains("BIND_AUTOFILL_SERVICE", ignoreCase = true))
        assertFalse(manifestText.contains("autofill_service_config", ignoreCase = true))

        val navClasses = SecondaryDestination::class.java.declaredClasses.map { it.simpleName }
        assertFalse(navClasses.any { it.contains("Autofill", ignoreCase = true) })

        val metricFields = PrivacyHealthMetrics::class.java.declaredFields.map { it.name }
        assertFalse(metricFields.any { it.contains("Autofill", ignoreCase = true) })
    }

    @Test
    fun testRetainedPasswordManagerCrudHistoryAndGeneratorRemainIntact() = runBlocking {
        val filesDir = tempFolder.newFolder("files_pm")
        val cacheDir = tempFolder.newFolder("cache_pm")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x19 }, false)
        }
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val gen = PasswordGeneratorEngine.generatePassword(
            PasswordGeneratorEngine.GeneratorOptions(length = 20, includeSymbols = true)
        )
        assertEquals(20, gen.password.length)

        val credId = "cred-retained-1"
        repository.saveItem(
            CredentialRecord(
                id = credId,
                serviceName = "CodeHost",
                username = "dev@example.test",
                password = "Old!Password#2025",
                websiteUrl = "https://codehost.example.test/login"
            )
        )
        repository.saveItem(
            CredentialRecord(
                id = credId,
                serviceName = "CodeHost",
                username = "dev@example.test",
                password = gen.password,
                websiteUrl = "https://codehost.example.test/login"
            )
        )

        val loaded = repository.getAllItemsForActiveSlot()
            .filterIsInstance<CredentialRecord>()
            .single { it.id == credId }
        assertEquals("https://codehost.example.test/login", loaded.websiteUrl)
        assertEquals(gen.password, loaded.password)
        assertEquals(1, loaded.passwordHistory.size)
        assertEquals("Old!Password#2025", loaded.passwordHistory.first().password)
    }
}
