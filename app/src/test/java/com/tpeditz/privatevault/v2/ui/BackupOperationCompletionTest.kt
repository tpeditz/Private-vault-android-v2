package com.tpeditz.privatevault.v2.ui

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupOperationCompletionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun flushesProviderStreamWithoutFileSync() {
        var flushes = 0
        val stream = object : ByteArrayOutputStream() {
            override fun flush() { flushes++ }
        }
        finalizeBackupOutput(stream) { error("Non-file provider stream cannot use file sync") }
        assertEquals(1, flushes)
    }

    @Test fun failedFlushIsNotReportedAsSuccess() {
        val stream = object : ByteArrayOutputStream() {
            override fun flush() { throw IOException("Storage write failed") }
        }
        assertThrows(IOException::class.java) {
            finalizeBackupOutput(stream) { error("Unexpected sync") }
        }
    }

    @Test fun requiredFileSyncFailurePropagates() {
        FileOutputStream(folder.newFile()).use { stream ->
            assertThrows(IOException::class.java) {
                finalizeBackupOutput(stream) { throw IOException("Storage sync failed") }
            }
        }
    }

    @Test fun completedOrCancelledOlderOperationCannotDeliverStaleResult() {
        val received = mutableListOf<String>()
        deliverCurrentOperationResult(1L, 2L) { received.add("old result") }
        deliverCurrentOperationResult(2L, 2L) { received.add("current result") }
        deliverCurrentOperationResult(2L, 0L) { received.add("cancelled result") }
        assertEquals(listOf("current result"), received)
    }
}
