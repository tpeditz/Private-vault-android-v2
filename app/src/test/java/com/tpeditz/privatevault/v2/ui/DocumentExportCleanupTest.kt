package com.tpeditz.privatevault.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentExportCleanupTest {
    @Test
    fun completedOutputIsPreservedEvenIfNotificationIsCancelled() {
        var cleanupCalls = 0
        val result = cleanupIncompleteDocumentExport(exportCompleted = true) {
            cleanupCalls++
            true
        }
        assertNull(result)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun unfinishedOutputIsRemovedWhenProviderAllowsIt() {
        var cleanupCalls = 0
        val result = cleanupIncompleteDocumentExport(exportCompleted = false) {
            cleanupCalls++
            true
        }
        assertEquals(true, result)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun providerCleanupFailureIsReported() {
        val result = cleanupIncompleteDocumentExport(exportCompleted = false) {
            throw java.io.IOException("Provider cannot remove output")
        }
        assertEquals(false, result)
    }
}
