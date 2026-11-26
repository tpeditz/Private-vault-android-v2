package com.tpeditz.privatevault.v2.ui

import java.io.FileOutputStream
import java.io.OutputStream

/**
 * The caller owns close/finalization via use(). A failed flush or required
 * regular-file sync must not be converted to a successful backup result.
 * Android provider-specific sync policy is supplied by the caller.
 */
internal fun finalizeBackupOutput(
    output: OutputStream,
    syncFile: (FileOutputStream) -> Unit
) {
    output.flush()
    if (output is FileOutputStream) syncFile(output)
}

/** Only the operation that still owns the current UI may deliver its result. */
internal inline fun deliverCurrentOperationResult(
    operationId: Long,
    activeOperationId: Long,
    deliver: () -> Unit
) {
    if (operationId == activeOperationId) deliver()
}
