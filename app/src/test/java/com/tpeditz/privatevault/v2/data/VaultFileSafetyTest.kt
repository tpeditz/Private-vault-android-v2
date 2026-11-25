package com.tpeditz.privatevault.v2.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultFileSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun normalPortableAssetIdsAndDirectoriesAreAccepted() {
        val root = temp.newFolder("assets")
        val asset = File(root, "photo_123.pvmedia")
        VaultFileSafety.requireAssetId("photo_123")
        assertEquals(asset, VaultFileSafety.requireWithin(root, asset))
        assertFalse(VaultFileSafety.isWithin(root, temp.newFolder("assets-old")))
    }

    @Test
    fun normalBackupAccountingRespectsLimits() {
        assertEquals(100L, VaultFileSafety.checkedSizeTotal(listOf(10L, 40L, 50L), 100L))
        assertThrows(IllegalArgumentException::class.java) {
            VaultFileSafety.checkedSizeTotal(listOf(10L, 91L), 100L)
        }
    }
}
