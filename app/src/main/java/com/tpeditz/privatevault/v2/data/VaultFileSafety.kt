package com.tpeditz.privatevault.v2.data

import java.io.File

/** Validate portable file IDs and confine file operations to an intended directory. */
internal object VaultFileSafety {
    private val assetIdPattern = Regex("^[A-Za-z0-9_-]{1,128}$")
    fun requireAssetId(id: String) {
        require(assetIdPattern.matches(id)) { "Backup contains an invalid asset identifier." }
    }
    fun isWithin(base: File, target: File): Boolean {
        val prefix = base.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return target.canonicalPath.startsWith(prefix)
    }
    fun requireWithin(base: File, target: File): File {
        require(isWithin(base, target)) { "Asset path is outside the expected private directory." }
        return target
    }
    fun checkedSizeTotal(parts: Iterable<Long>, maximum: Long): Long {
        var sum = 0L
        for (part in parts) {
            require(part >= 0 && part <= maximum - sum) { "Invalid or oversized backup payload." }
            sum += part
        }
        return sum
    }
}
