package com.tpeditz.privatevault.v2.data

import java.util.UUID

/**
 * Decrypted domain models held in volatile memory only during an active, unlocked vault session.
 * All models serialize to a single versioned UTF-8 JSON envelope before AES-256-GCM encryption.
 */
sealed interface VaultItem {
    val id: String
    val createdAtEpochMs: Long
    val updatedAtEpochMs: Long
}

data class ChecklistEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isChecked: Boolean = false
)

enum class NoteBodyFormat {
    PLAIN_TEXT,
    MARKDOWN,
    CHECKLIST
}

data class NoteRecord(
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    val format: NoteBodyFormat = NoteBodyFormat.PLAIN_TEXT,
    val checklistItems: List<ChecklistEntry> = emptyList(),
    val category: String = "Personal",
    val folderName: String = VaultFolder.DEFAULT_FOLDER_NAME,
    val folderId: String = VaultFolder.DEFAULT_NOTE_FOLDER_ID,
    val tags: List<String> = emptyList(),
    val colorAccentIndex: Int = 0,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

data class CustomCredentialField(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val isSecret: Boolean = true
)

data class PasswordHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val password: String,
    val changedAtEpochMs: Long = System.currentTimeMillis()
)

data class CredentialRecord(
    override val id: String = UUID.randomUUID().toString(),
    val serviceName: String,
    val accountLabel: String = "",
    val username: String,
    val password: String,
    val websiteUrl: String = "",
    val notes: String = "",
    val category: String = "Logins",
    val folderName: String = VaultFolder.DEFAULT_FOLDER_NAME,
    val folderId: String = VaultFolder.DEFAULT_CREDENTIAL_FOLDER_ID,
    val tags: List<String> = emptyList(),
    val customFields: List<CustomCredentialField> = emptyList(),
    val passwordHistory: List<PasswordHistoryEntry> = emptyList(),
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

enum class MediaKind {
    PHOTO,
    VIDEO
}

data class MediaRecord(
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val originalFileName: String,
    val mimeType: String,
    val mediaKind: MediaKind,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val chunkCount: Int,
    val formatVersion: Int = 1,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMs: Long = 0L,
    val fileMediaKeyBase64: String,
    val thumbnailKeyBase64: String,
    val ciphertextSha256Hex: String,
    val albumId: String = "",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

data class DocumentRecord(
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val originalFileName: String,
    val mimeType: String,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val chunkCount: Int,
    val formatVersion: Int = 1,
    val fileKeyBase64: String,
    val ciphertextSha256Hex: String,
    val category: String = "Documents",
    val folderId: String = "",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

enum class SecureLinkCategory {
    MOVIES,
    SERIES,
    VIDEOS,
    WEBSITES,
    OTHER
}

data class SecureLinkRecord(
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val category: SecureLinkCategory = SecureLinkCategory.WEBSITES,
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isWatched: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

data class VaultAlbumRecord(
    override val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coverMediaId: String = "",
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

enum class TotpAlgorithm {
    SHA1,
    SHA256,
    SHA512
}

data class AuthenticatorRecord(
    override val id: String = UUID.randomUUID().toString(),
    val serviceName: String,
    val accountName: String,
    val secret: String,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem

enum class FolderScope {
    NOTES,
    CREDENTIALS,
    DOCUMENTS
}

data class VaultFolder(
    override val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scope: FolderScope,
    override val createdAtEpochMs: Long = System.currentTimeMillis(),
    override val updatedAtEpochMs: Long = System.currentTimeMillis()
) : VaultItem {
    companion object {
        const val DEFAULT_FOLDER_NAME = "General"
        const val RESERVED_ALL_FILTER_NAME = "All"
        const val DEFAULT_NOTE_FOLDER_ID = "builtin-folder-notes-general"
        const val DEFAULT_CREDENTIAL_FOLDER_ID = "builtin-folder-credentials-general"
        const val DEFAULT_DOCUMENT_FOLDER_ID = "builtin-folder-documents-general"

        fun isReservedAllName(candidate: String): Boolean {
            return candidate.trim().equals(RESERVED_ALL_FILTER_NAME, ignoreCase = true)
        }

        fun isDefaultGeneralName(candidate: String): Boolean {
            return candidate.trim().equals(DEFAULT_FOLDER_NAME, ignoreCase = true)
        }
    }
}
