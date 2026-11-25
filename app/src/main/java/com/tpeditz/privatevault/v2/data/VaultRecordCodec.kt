package com.tpeditz.privatevault.v2.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Deterministic JSON serializer/deserializer for encrypted record payloads in Private Vault V2.
 * Fully backward-compatible with V1 records (Notes, Credentials, Folders, Media) and adds
 * first-class support for Documents, Secure Links, Albums, Authenticators, and Password History.
 */
object VaultRecordCodec {

    private const val TYPE_NOTE = "NOTE"
    private const val TYPE_CREDENTIAL = "CREDENTIAL"
    private const val TYPE_FOLDER = "FOLDER"
    private const val TYPE_MEDIA = "MEDIA"
    private const val TYPE_DOCUMENT = "DOCUMENT"
    private const val TYPE_LINK = "LINK"
    private const val TYPE_ALBUM = "ALBUM"
    private const val TYPE_AUTHENTICATOR = "AUTHENTICATOR"

    fun encode(item: VaultItem): ByteArray {
        val root = JSONObject()
        when (item) {
            is NoteRecord -> {
                root.put("recordType", TYPE_NOTE)
                root.put("id", item.id)
                root.put("title", item.title)
                root.put("body", item.body)
                root.put("format", item.format.name)
                root.put("category", item.category)
                root.put("folderName", item.folderName)
                root.put("folderId", item.folderId)
                root.put("colorAccentIndex", item.colorAccentIndex)
                root.put("isPinned", item.isPinned)
                root.put("isFavorite", item.isFavorite)
                root.put("isArchived", item.isArchived)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)

                val tagsArray = JSONArray()
                item.tags.forEach { tagsArray.put(it) }
                root.put("tags", tagsArray)

                val checklistArray = JSONArray()
                item.checklistItems.forEach { entry ->
                    val obj = JSONObject()
                    obj.put("id", entry.id)
                    obj.put("text", entry.text)
                    obj.put("isChecked", entry.isChecked)
                    checklistArray.put(obj)
                }
                root.put("checklistItems", checklistArray)
            }

            is CredentialRecord -> {
                root.put("recordType", TYPE_CREDENTIAL)
                root.put("id", item.id)
                root.put("serviceName", item.serviceName)
                root.put("accountLabel", item.accountLabel)
                root.put("username", item.username)
                root.put("password", item.password)
                root.put("websiteUrl", item.websiteUrl)
                root.put("notes", item.notes)
                root.put("category", item.category)
                root.put("folderName", item.folderName)
                root.put("folderId", item.folderId)
                root.put("isFavorite", item.isFavorite)
                root.put("isPinned", item.isPinned)
                root.put("isArchived", item.isArchived)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)

                val tagsArray = JSONArray()
                item.tags.forEach { tagsArray.put(it) }
                root.put("tags", tagsArray)

                val customArray = JSONArray()
                item.customFields.forEach { field ->
                    val obj = JSONObject()
                    obj.put("id", field.id)
                    obj.put("label", field.label)
                    obj.put("value", field.value)
                    obj.put("isSecret", field.isSecret)
                    customArray.put(obj)
                }
                root.put("customFields", customArray)

                val historyArray = JSONArray()
                item.passwordHistory.forEach { hist ->
                    val obj = JSONObject()
                    obj.put("id", hist.id)
                    obj.put("password", hist.password)
                    obj.put("changedAtEpochMs", hist.changedAtEpochMs)
                    historyArray.put(obj)
                }
                root.put("passwordHistory", historyArray)
            }

            is VaultFolder -> {
                root.put("recordType", TYPE_FOLDER)
                root.put("id", item.id)
                root.put("name", item.name)
                root.put("scope", item.scope.name)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)
            }

            is MediaRecord -> {
                root.put("recordType", TYPE_MEDIA)
                root.put("id", item.id)
                root.put("title", item.title)
                root.put("originalFileName", item.originalFileName)
                root.put("mimeType", item.mimeType)
                root.put("mediaKind", item.mediaKind.name)
                root.put("originalSizeBytes", item.originalSizeBytes)
                root.put("encryptedSizeBytes", item.encryptedSizeBytes)
                root.put("chunkCount", item.chunkCount)
                root.put("formatVersion", item.formatVersion)
                root.put("widthPx", item.widthPx)
                root.put("heightPx", item.heightPx)
                root.put("durationMs", item.durationMs)
                root.put("fileMediaKeyBase64", item.fileMediaKeyBase64)
                root.put("thumbnailKeyBase64", item.thumbnailKeyBase64)
                root.put("ciphertextSha256Hex", item.ciphertextSha256Hex)
                root.put("albumId", item.albumId)
                root.put("notes", item.notes)
                root.put("isFavorite", item.isFavorite)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)

                val tagsArray = JSONArray()
                item.tags.forEach { tagsArray.put(it) }
                root.put("tags", tagsArray)
            }

            is DocumentRecord -> {
                root.put("recordType", TYPE_DOCUMENT)
                root.put("id", item.id)
                root.put("title", item.title)
                root.put("originalFileName", item.originalFileName)
                root.put("mimeType", item.mimeType)
                root.put("originalSizeBytes", item.originalSizeBytes)
                root.put("encryptedSizeBytes", item.encryptedSizeBytes)
                root.put("chunkCount", item.chunkCount)
                root.put("formatVersion", item.formatVersion)
                root.put("fileKeyBase64", item.fileKeyBase64)
                root.put("ciphertextSha256Hex", item.ciphertextSha256Hex)
                root.put("category", item.category)
                root.put("folderId", item.folderId)
                root.put("notes", item.notes)
                root.put("isFavorite", item.isFavorite)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)

                val tagsArray = JSONArray()
                item.tags.forEach { tagsArray.put(it) }
                root.put("tags", tagsArray)
            }

            is SecureLinkRecord -> {
                root.put("recordType", TYPE_LINK)
                root.put("id", item.id)
                root.put("title", item.title)
                root.put("url", item.url)
                root.put("category", item.category.name)
                root.put("notes", item.notes)
                root.put("isFavorite", item.isFavorite)
                root.put("isWatched", item.isWatched)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)

                val tagsArray = JSONArray()
                item.tags.forEach { tagsArray.put(it) }
                root.put("tags", tagsArray)
            }

            is VaultAlbumRecord -> {
                root.put("recordType", TYPE_ALBUM)
                root.put("id", item.id)
                root.put("name", item.name)
                root.put("coverMediaId", item.coverMediaId)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)
            }

            is AuthenticatorRecord -> {
                root.put("recordType", TYPE_AUTHENTICATOR)
                root.put("id", item.id)
                root.put("serviceName", item.serviceName)
                root.put("accountName", item.accountName)
                root.put("secret", item.secret)
                root.put("digits", item.digits)
                root.put("periodSeconds", item.periodSeconds)
                root.put("algorithm", item.algorithm.name)
                root.put("isFavorite", item.isFavorite)
                root.put("isTrashed", item.isTrashed)
                root.put("createdAtEpochMs", item.createdAtEpochMs)
                root.put("updatedAtEpochMs", item.updatedAtEpochMs)
            }
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): VaultItem {
        val jsonString = String(bytes, StandardCharsets.UTF_8)
        val root = JSONObject(jsonString)
        return decodeFromJsonObject(root)
    }

    fun decodeFromJsonObject(root: JSONObject): VaultItem {
        val rawType = when {
            root.has("recordType") -> root.optString("recordType")
            root.has("type") -> root.optString("type")
            else -> ""
        }.trim().uppercase()
        return when (rawType) {
            TYPE_NOTE -> {
                val tags = mutableListOf<String>()
                val tagsArray = root.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.optString(i))
                    }
                }

                val checklist = mutableListOf<ChecklistEntry>()
                val checklistArray = root.optJSONArray("checklistItems")
                if (checklistArray != null) {
                    for (i in 0 until checklistArray.length()) {
                        val obj = checklistArray.optJSONObject(i) ?: continue
                        checklist.add(
                            ChecklistEntry(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                text = obj.optString("text", ""),
                                isChecked = obj.optBoolean("isChecked", false)
                            )
                        )
                    }
                }

                val formatStr = root.optString("format", NoteBodyFormat.PLAIN_TEXT.name)
                val format = runCatching { NoteBodyFormat.valueOf(formatStr) }
                    .getOrDefault(NoteBodyFormat.PLAIN_TEXT)

                val rawFolderName = root.optString("folderName", VaultFolder.DEFAULT_FOLDER_NAME)
                    .ifBlank { VaultFolder.DEFAULT_FOLDER_NAME }
                val rawFolderId = root.optString("folderId", "").ifBlank {
                    if (VaultFolder.isDefaultGeneralName(rawFolderName)) {
                        VaultFolder.DEFAULT_NOTE_FOLDER_ID
                    } else {
                        ""
                    }
                }

                NoteRecord(
                    id = root.getString("id"),
                    title = root.optString("title", ""),
                    body = root.optString("body", ""),
                    format = format,
                    checklistItems = checklist,
                    category = root.optString("category", "Personal"),
                    folderName = rawFolderName,
                    folderId = rawFolderId,
                    tags = tags,
                    colorAccentIndex = root.optInt("colorAccentIndex", 0),
                    isPinned = root.optBoolean("isPinned", false),
                    isFavorite = root.optBoolean("isFavorite", false),
                    isArchived = root.optBoolean("isArchived", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_CREDENTIAL -> {
                val tags = mutableListOf<String>()
                val tagsArray = root.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.optString(i))
                    }
                }

                val customFields = mutableListOf<CustomCredentialField>()
                val customArray = root.optJSONArray("customFields")
                if (customArray != null) {
                    for (i in 0 until customArray.length()) {
                        val obj = customArray.optJSONObject(i) ?: continue
                        customFields.add(
                            CustomCredentialField(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                label = obj.optString("label", ""),
                                value = obj.optString("value", ""),
                                isSecret = obj.optBoolean("isSecret", true)
                            )
                        )
                    }
                }

                val passwordHistory = mutableListOf<PasswordHistoryEntry>()
                val historyArray = root.optJSONArray("passwordHistory")
                if (historyArray != null) {
                    for (i in 0 until historyArray.length()) {
                        val obj = historyArray.optJSONObject(i) ?: continue
                        passwordHistory.add(
                            PasswordHistoryEntry(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                password = obj.optString("password", ""),
                                changedAtEpochMs = obj.optLong("changedAtEpochMs", 0L)
                            )
                        )
                    }
                }

                val rawFolderName = root.optString("folderName", VaultFolder.DEFAULT_FOLDER_NAME)
                    .ifBlank { VaultFolder.DEFAULT_FOLDER_NAME }
                val rawFolderId = root.optString("folderId", "").ifBlank {
                    if (VaultFolder.isDefaultGeneralName(rawFolderName)) {
                        VaultFolder.DEFAULT_CREDENTIAL_FOLDER_ID
                    } else {
                        ""
                    }
                }

                CredentialRecord(
                    id = root.getString("id"),
                    serviceName = root.optString("serviceName", ""),
                    accountLabel = root.optString("accountLabel", ""),
                    username = root.optString("username", ""),
                    password = root.optString("password", ""),
                    websiteUrl = root.optString("websiteUrl", ""),
                    notes = root.optString("notes", ""),
                    category = root.optString("category", "Logins"),
                    folderName = rawFolderName,
                    folderId = rawFolderId,
                    tags = tags,
                    customFields = customFields,
                    passwordHistory = passwordHistory,
                    isFavorite = root.optBoolean("isFavorite", false),
                    isPinned = root.optBoolean("isPinned", false),
                    isArchived = root.optBoolean("isArchived", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_FOLDER -> {
                val scopeStr = root.optString("scope", FolderScope.NOTES.name)
                val scope = runCatching { FolderScope.valueOf(scopeStr) }
                    .getOrDefault(FolderScope.NOTES)
                VaultFolder(
                    id = root.getString("id"),
                    name = root.optString("name", VaultFolder.DEFAULT_FOLDER_NAME),
                    scope = scope,
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_MEDIA -> {
                val tags = mutableListOf<String>()
                val tagsArray = root.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.optString(i))
                    }
                }
                val kindStr = root.optString("mediaKind", MediaKind.PHOTO.name)
                val kind = runCatching { MediaKind.valueOf(kindStr) }
                    .getOrDefault(MediaKind.PHOTO)

                val fileMediaKeyB64 = root.optString("fileMediaKeyBase64", "")
                val thumbKeyB64 = root.optString("thumbnailKeyBase64", "")
                val sha256Hex = root.optString("ciphertextSha256Hex", "")
                require(fileMediaKeyB64.isNotBlank() && thumbKeyB64.isNotBlank() && sha256Hex.length == 64) {
                    "Encrypted MediaRecord is missing required cryptographic key or SHA-256 metadata."
                }

                MediaRecord(
                    id = root.getString("id"),
                    title = root.optString("title", "Encrypted Media"),
                    originalFileName = root.optString("originalFileName", ""),
                    mimeType = root.optString("mimeType", "application/octet-stream"),
                    mediaKind = kind,
                    originalSizeBytes = root.optLong("originalSizeBytes", 0L),
                    encryptedSizeBytes = root.optLong("encryptedSizeBytes", 0L),
                    chunkCount = root.optInt("chunkCount", 1),
                    formatVersion = root.optInt("formatVersion", 1),
                    widthPx = root.optInt("widthPx", 0),
                    heightPx = root.optInt("heightPx", 0),
                    durationMs = root.optLong("durationMs", 0L),
                    fileMediaKeyBase64 = fileMediaKeyB64,
                    thumbnailKeyBase64 = thumbKeyB64,
                    ciphertextSha256Hex = sha256Hex,
                    albumId = root.optString("albumId", ""),
                    tags = tags,
                    notes = root.optString("notes", ""),
                    isFavorite = root.optBoolean("isFavorite", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_DOCUMENT -> {
                val tags = mutableListOf<String>()
                val tagsArray = root.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.optString(i))
                    }
                }
                val fileKeyB64 = root.optString("fileKeyBase64", "")
                val sha256Hex = root.optString("ciphertextSha256Hex", "")
                require(fileKeyB64.isNotBlank() && sha256Hex.length == 64) {
                    "Encrypted DocumentRecord is missing cryptographic key or SHA-256 metadata."
                }

                DocumentRecord(
                    id = root.getString("id"),
                    title = root.optString("title", "Encrypted Document"),
                    originalFileName = root.optString("originalFileName", ""),
                    mimeType = root.optString("mimeType", "application/pdf"),
                    originalSizeBytes = root.optLong("originalSizeBytes", 0L),
                    encryptedSizeBytes = root.optLong("encryptedSizeBytes", 0L),
                    chunkCount = root.optInt("chunkCount", 1),
                    formatVersion = root.optInt("formatVersion", 1),
                    fileKeyBase64 = fileKeyB64,
                    ciphertextSha256Hex = sha256Hex,
                    category = root.optString("category", "Documents"),
                    folderId = root.optString("folderId", ""),
                    tags = tags,
                    notes = root.optString("notes", ""),
                    isFavorite = root.optBoolean("isFavorite", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_LINK -> {
                val tags = mutableListOf<String>()
                val tagsArray = root.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tags.add(tagsArray.optString(i))
                    }
                }
                val catStr = root.optString("category", SecureLinkCategory.WEBSITES.name)
                val category = runCatching { SecureLinkCategory.valueOf(catStr) }
                    .getOrDefault(SecureLinkCategory.WEBSITES)

                SecureLinkRecord(
                    id = root.getString("id"),
                    title = root.optString("title", "Secure Link"),
                    url = root.optString("url", ""),
                    category = category,
                    notes = root.optString("notes", ""),
                    tags = tags,
                    isFavorite = root.optBoolean("isFavorite", false),
                    isWatched = root.optBoolean("isWatched", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_ALBUM -> {
                VaultAlbumRecord(
                    id = root.getString("id"),
                    name = root.optString("name", "New Album"),
                    coverMediaId = root.optString("coverMediaId", ""),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            TYPE_AUTHENTICATOR -> {
                val algStr = root.optString("algorithm", TotpAlgorithm.SHA1.name)
                val alg = runCatching { TotpAlgorithm.valueOf(algStr) }
                    .getOrDefault(TotpAlgorithm.SHA1)

                AuthenticatorRecord(
                    id = root.getString("id"),
                    serviceName = root.optString("serviceName", "Service"),
                    accountName = root.optString("accountName", ""),
                    secret = root.optString("secret", ""),
                    digits = root.optInt("digits", 6),
                    periodSeconds = root.optInt("periodSeconds", 30),
                    algorithm = alg,
                    isFavorite = root.optBoolean("isFavorite", false),
                    isTrashed = root.optBoolean("isTrashed", false),
                    createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
                    updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
                )
            }

            else -> throw IllegalArgumentException("Unsupported vault recordType: $rawType")
        }
    }
}
