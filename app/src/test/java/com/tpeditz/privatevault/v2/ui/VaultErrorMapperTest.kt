package com.tpeditz.privatevault.v2.ui

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException

class VaultErrorMapperTest {

    @Test
    fun testMapsBadDecryptAndAeadExceptionsToPlainEnglishWithoutTechnicalLeak() {
        val mappedAead = VaultErrorMapper.mapRestoreError(AEADBadTagException("Tag mismatch"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.AUTH_OR_DAMAGED_PASSWORD, mappedAead.kind)
        assertEquals("Unable to restore backup", mappedAead.title)
        assertEquals(
            "The backup password is incorrect, or the backup file is damaged. Check your backup password and try again.",
            mappedAead.message
        )
        assertTrue(mappedAead.canRetryPassword)
        assertTrue(mappedAead.canChooseAnotherFile)
        assertFalse(mappedAead.message.contains("AEAD", ignoreCase = true))

        val mappedNativeOpenSsl = VaultErrorMapper.mapRestoreError(
            RuntimeException("android.crypto.Cipher: BAD_DECRYPT at 0x7f")
        )
        assertEquals(VaultErrorMapper.RestoreErrorKind.AUTH_OR_DAMAGED_PASSWORD, mappedNativeOpenSsl.kind)
        assertTrue(mappedNativeOpenSsl.canRetryPassword)
        assertFalse(mappedNativeOpenSsl.message.contains("BAD_DECRYPT", ignoreCase = true))
    }

    @Test
    fun testMapsStorageMissingAssetsCorruptUnsupportedLockedAndCancelledCleanly() {
        val storage = VaultErrorMapper.mapRestoreError(IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.INSUFFICIENT_STORAGE, storage.kind)
        assertFalse(storage.canRetryPassword)

        val missingAssets = VaultErrorMapper.mapRestoreError(IllegalStateException("Missing encrypted media payload for item_1"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.MISSING_REFERENCED_ASSETS, missingAssets.kind)
        assertTrue(missingAssets.canChooseAnotherFile)

        val truncated = VaultErrorMapper.mapRestoreError(EOFException("Unexpected EOF"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.INCOMPLETE_OR_DAMAGED, truncated.kind)

        val corruptJson = VaultErrorMapper.mapRestoreError(JSONException("Unterminated object"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.INCOMPLETE_OR_DAMAGED, corruptJson.kind)

        val badHeader = VaultErrorMapper.mapRestoreError(IllegalArgumentException("Unrecognized backup file header."))
        assertEquals(VaultErrorMapper.RestoreErrorKind.UNSUPPORTED_FORMAT, badHeader.kind)

        val locked = VaultErrorMapper.mapRestoreError(IllegalStateException("Vault is locked"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.VAULT_LOCKED, locked.kind)

        val unopenable = VaultErrorMapper.mapRestoreError(FileNotFoundException("/storage/emulated/0/missing.pvault"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.FILE_UNACCESSIBLE, unopenable.kind)
        assertFalse(unopenable.message.contains("/storage/"))

        val cancelled = VaultErrorMapper.mapRestoreError(CancellationException("cancelled"))
        assertEquals(VaultErrorMapper.RestoreErrorKind.CANCELLED, cancelled.kind)

        val cloud401 = VaultErrorMapper.mapCloudError(null, httpCode = 401)
        assertTrue(cloud401.contains("Reconnect your Google account"))

        val offline = VaultErrorMapper.mapCloudError(UnknownHostException("drive.googleapis.com"))
        assertTrue(offline.contains("internet connection"))
    }
}
