package com.tpeditz.privatevault.v2.data

data class EmbeddedMediaPayload(
    val mediaId: String,
    val formatVersion: Int,
    val chunkCount: Int,
    val ciphertextSha256: String,
    val mediaCiphertextBytes: ByteArray,
    val thumbCiphertextBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddedMediaPayload
        return mediaId == other.mediaId && ciphertextSha256 == other.ciphertextSha256
    }

    override fun hashCode(): Int = mediaId.hashCode()
}

data class EmbeddedDocumentPayload(
    val documentId: String,
    val formatVersion: Int,
    val chunkCount: Int,
    val ciphertextSha256: String,
    val documentCiphertextBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddedDocumentPayload
        return documentId == other.documentId && ciphertextSha256 == other.ciphertextSha256
    }

    override fun hashCode(): Int = documentId.hashCode()
}

enum class RestoreMode {
    MERGE_KEEP_NEWEST,
    REPLACE_ACTIVE_VAULT
}

enum class BackupExportScope(val label: String) {
    ALL_DATA("All Vault Data (Up to 2 GB)"),
    NOTES_PASSWORDS_LINKS("Notes, Passwords & Links"),
    NOTES_ONLY("Notes Only"),
    PASSWORDS_ONLY("Passwords Only"),
    MEDIA_ONLY("Media Only (Photos & Videos)"),
    DOCUMENTS_ONLY("Documents Only")
}

data class BackupPayload(
    val exportedAtEpochMs: Long,
    val appVersionName: String,
    val applicationId: String = "com.tpeditz.privatevault.v2",
    val items: List<VaultItem>,
    val mediaPayloads: Map<String, EmbeddedMediaPayload> = emptyMap(),
    val documentPayloads: Map<String, EmbeddedDocumentPayload> = emptyMap(),
    val exportScope: BackupExportScope = BackupExportScope.ALL_DATA,
    val isExportScopeKnown: Boolean = false
) {
    val legacyMediaBlobs: Map<String, EmbeddedMediaPayload> get() = mediaPayloads
    val notesCount: Int get() = items.count { it is NoteRecord }
    val credentialsCount: Int get() = items.count { it is CredentialRecord }
    val foldersCount: Int get() = items.count { it is VaultFolder }
    val mediaCount: Int get() = items.count { it is MediaRecord }
    val documentsCount: Int get() = items.count { it is DocumentRecord }
    val linksCount: Int get() = items.count { it is SecureLinkRecord }
    val authenticatorsCount: Int get() = items.count { it is AuthenticatorRecord }

    val isVerifiedFullBackup: Boolean
        get() = isExportScopeKnown && exportScope == BackupExportScope.ALL_DATA

    val isPartialCategoryBackup: Boolean
        get() = isExportScopeKnown && exportScope != BackupExportScope.ALL_DATA

    val scopeDisplayLabel: String
        get() = when {
            !isExportScopeKnown -> "Backup scope unknown"
            exportScope == BackupExportScope.ALL_DATA -> "Full vault backup"
            else -> exportScope.label
        }

    val isEmptyBackup: Boolean
        get() = items.none {
            it is NoteRecord || it is CredentialRecord || it is MediaRecord ||
                it is DocumentRecord || it is SecureLinkRecord || it is AuthenticatorRecord
        }
}

fun filterItemsForBackupScope(allItems: List<VaultItem>, scope: BackupExportScope): List<VaultItem> {
    return when (scope) {
        BackupExportScope.ALL_DATA -> allItems
        BackupExportScope.NOTES_PASSWORDS_LINKS -> allItems.filter {
            it is NoteRecord ||
                it is CredentialRecord ||
                it is SecureLinkRecord ||
                (it is VaultFolder && (it.scope == FolderScope.NOTES || it.scope == FolderScope.CREDENTIALS))
        }
        BackupExportScope.NOTES_ONLY -> allItems.filter {
            it is NoteRecord || (it is VaultFolder && it.scope == FolderScope.NOTES)
        }
        BackupExportScope.PASSWORDS_ONLY -> allItems.filter {
            it is CredentialRecord || (it is VaultFolder && it.scope == FolderScope.CREDENTIALS)
        }
        BackupExportScope.MEDIA_ONLY -> allItems.filter {
            it is MediaRecord || it is VaultAlbumRecord
        }
        BackupExportScope.DOCUMENTS_ONLY -> allItems.filter {
            it is DocumentRecord || (it is VaultFolder && it.scope == FolderScope.DOCUMENTS)
        }
    }
}

data class StagedBackupRestore(
    val payload: BackupPayload,
    val stagingDir: java.io.File
)

class CorruptedVaultRecordsException(
    val corruptedCount: Int,
    message: String = "Some vault items could not be read. Your backup was not replaced."
) : IllegalStateException(message)
