package com.tpeditz.privatevault.v2.ui

/**
 * Null means the verified completed output must be preserved.
 * True/false indicates whether operation-owned incomplete output was removed.
 */
internal fun cleanupIncompleteDocumentExport(
    exportCompleted: Boolean,
    deleteIncompleteOutput: () -> Boolean
): Boolean? {
    if (exportCompleted) return null
    return runCatching(deleteIncompleteOutput).getOrDefault(false)
}
