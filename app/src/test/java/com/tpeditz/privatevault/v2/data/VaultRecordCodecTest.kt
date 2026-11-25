package com.tpeditz.privatevault.v2.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class VaultRecordCodecTest {

    @Test
    fun testNoteRecordRoundtrip() {
        val note = NoteRecord(
            id = "note-uuid-001",
            title = "Project Roadmap",
            body = "# Master Architecture\n- Secure Enclave\n- Streaming Backup",
            format = NoteBodyFormat.MARKDOWN,
            category = "Work",
            tags = listOf("crypto", "v2", "release"),
            checklistItems = listOf(
                ChecklistEntry("chk-1", "Initialize repo", isChecked = true),
                ChecklistEntry("chk-2", "Add unit tests", isChecked = false)
            ),
            isPinned = true,
            isFavorite = true,
            createdAtEpochMs = 1700000000000L,
            updatedAtEpochMs = 1700000050000L
        )

        val encoded = VaultRecordCodec.encode(note)
        val decoded = VaultRecordCodec.decode(encoded) as NoteRecord

        assertEquals(note.id, decoded.id)
        assertEquals(note.title, decoded.title)
        assertEquals(note.body, decoded.body)
        assertEquals(note.format, decoded.format)
        assertEquals(note.category, decoded.category)
        assertEquals(note.tags, decoded.tags)
        assertEquals(note.checklistItems.size, decoded.checklistItems.size)
        assertEquals(note.checklistItems[0].text, decoded.checklistItems[0].text)
        assertEquals(note.checklistItems[0].isChecked, decoded.checklistItems[0].isChecked)
        assertEquals(note.isPinned, decoded.isPinned)
        assertEquals(note.isFavorite, decoded.isFavorite)
    }

    @Test
    fun testCredentialRecordWithPasswordHistoryRoundtrip() {
        val cred = CredentialRecord(
            id = "cred-uuid-002",
            serviceName = "Example fixture service",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD_1",
            websiteUrl = "https://account.example.test/",
            category = "Email",
            passwordHistory = listOf(
                PasswordHistoryEntry(password = "TEST_ONLY_OLD_PASSWORD_1", changedAtEpochMs = 1690000000000L),
                PasswordHistoryEntry(password = "TEST_ONLY_OLD_PASSWORD_2", changedAtEpochMs = 1660000000000L)
            ),
            customFields = listOf(
                CustomCredentialField(label = "Example field", value = "TEST_ONLY_FIELD_VALUE", isSecret = true)
            ),
            isFavorite = true
        )

        val encoded = VaultRecordCodec.encode(cred)
        val decoded = VaultRecordCodec.decode(encoded) as CredentialRecord

        assertEquals(cred.id, decoded.id)
        assertEquals(cred.serviceName, decoded.serviceName)
        assertEquals(cred.username, decoded.username)
        assertEquals(cred.password, decoded.password)
        assertEquals(cred.websiteUrl, decoded.websiteUrl)
        assertEquals(2, decoded.passwordHistory.size)
        assertEquals("TEST_ONLY_OLD_PASSWORD_1", decoded.passwordHistory[0].password)
        assertEquals(1, decoded.customFields.size)
        assertEquals("Example field", decoded.customFields[0].label)
        assertTrue(decoded.customFields[0].isSecret)
    }

    @Test
    fun testMediaRecordWithAlbumIdRoundtrip() {
        val media = MediaRecord(
            id = "media-uuid-003",
            title = "Passport Scan",
            originalFileName = "passport_scan.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = 2048576L,
            encryptedSizeBytes = 2049000L,
            chunkCount = 8,
            widthPx = 1920,
            heightPx = 1080,
            fileMediaKeyBase64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=",
            thumbnailKeyBase64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            ciphertextSha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            albumId = "album-travel-docs",
            tags = listOf("passport", "travel"),
            isFavorite = true
        )

        val encoded = VaultRecordCodec.encode(media)
        val decoded = VaultRecordCodec.decode(encoded) as MediaRecord

        assertEquals(media.id, decoded.id)
        assertEquals(media.title, decoded.title)
        assertEquals(media.originalFileName, decoded.originalFileName)
        assertEquals(media.mimeType, decoded.mimeType)
        assertEquals(media.originalSizeBytes, decoded.originalSizeBytes)
        assertEquals(media.albumId, decoded.albumId)
        assertEquals(1920, decoded.widthPx)
        assertEquals(1080, decoded.heightPx)
    }

    @Test
    fun testDocumentRecordRoundtrip() {
        val doc = DocumentRecord(
            id = "doc-uuid-004",
            title = "Tax Return 2025",
            originalFileName = "tax_2025.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = 512000L,
            encryptedSizeBytes = 512500L,
            chunkCount = 2,
            fileKeyBase64 = "ZmlsZWtleTEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
            ciphertextSha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            category = "Financial",
            tags = listOf("taxes", "2025"),
            isFavorite = true
        )

        val encoded = VaultRecordCodec.encode(doc)
        val decoded = VaultRecordCodec.decode(encoded) as DocumentRecord

        assertEquals(doc.id, decoded.id)
        assertEquals(doc.title, decoded.title)
        assertEquals(doc.originalFileName, decoded.originalFileName)
        assertEquals(doc.mimeType, decoded.mimeType)
        assertEquals(doc.originalSizeBytes, decoded.originalSizeBytes)
        assertEquals("Financial", decoded.category)
    }

    @Test
    fun testSecureLinkRecordRoundtrip() {
        val link = SecureLinkRecord(
            id = "link-uuid-005",
            title = "Private Vault Repository",
            url = "https://media.example.test/watch",
            category = SecureLinkCategory.WEBSITES,
            tags = listOf("android", "kotlin", "open-source"),
            notes = "Official source repo"
        )

        val encoded = VaultRecordCodec.encode(link)
        val decoded = VaultRecordCodec.decode(encoded) as SecureLinkRecord

        assertEquals(link.id, decoded.id)
        assertEquals(link.url, decoded.url)
        assertEquals(link.title, decoded.title)
        assertEquals(3, decoded.tags.size)
        assertEquals("Official source repo", decoded.notes)
    }

    @Test
    fun testVaultAlbumRecordRoundtrip() {
        val album = VaultAlbumRecord(
            id = "album-uuid-006",
            name = "Family Vacation 2025",
            coverMediaId = "media-cover-01"
        )

        val encoded = VaultRecordCodec.encode(album)
        val decoded = VaultRecordCodec.decode(encoded) as VaultAlbumRecord

        assertEquals(album.id, decoded.id)
        assertEquals(album.name, decoded.name)
        assertEquals(album.coverMediaId, decoded.coverMediaId)
    }

    @Test
    fun testAuthenticatorRecordRoundtrip() {
        val auth = AuthenticatorRecord(
            id = "auth-uuid-007",
            serviceName = "Example fixture authenticator",
            accountName = "fixture-user@example.test",
            secret = "MZXW6YTBOI======",
            algorithm = TotpAlgorithm.SHA256,
            digits = 8,
            periodSeconds = 60
        )

        val encoded = VaultRecordCodec.encode(auth)
        val decoded = VaultRecordCodec.decode(encoded) as AuthenticatorRecord

        assertEquals(auth.id, decoded.id)
        assertEquals(auth.serviceName, decoded.serviceName)
        assertEquals(auth.accountName, decoded.accountName)
        assertEquals(auth.secret, decoded.secret)
        assertEquals(TotpAlgorithm.SHA256, decoded.algorithm)
        assertEquals(8, decoded.digits)
        assertEquals(60, decoded.periodSeconds)
    }

    @Test
    fun testFolderRecordRoundtrip() {
        val folder = VaultFolder(
            id = "folder-uuid-008",
            name = "Banking & Crypto",
            scope = FolderScope.CREDENTIALS
        )

        val encoded = VaultRecordCodec.encode(folder)
        val decoded = VaultRecordCodec.decode(encoded) as VaultFolder

        assertEquals(folder.id, decoded.id)
        assertEquals(folder.name, decoded.name)
        assertEquals(folder.scope, decoded.scope)
    }

    @Test
    fun testLegacyV1JsonBackwardCompatibility() {
        // Construct a legacy V1 note JSON with no format, no checklist, no tags
        val legacyJson = JSONObject().apply {
            put("recordType", "NOTE")
            put("id", "legacy-note-01")
            put("title", "Legacy V1 Note")
            put("body", "Synthetic legacy note")
            put("createdAtEpochMs", 1680000000000L)
            put("updatedAtEpochMs", 1680000000000L)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val decoded = VaultRecordCodec.decode(legacyJson)
        assertTrue(decoded is NoteRecord)
        val note = decoded as NoteRecord
        assertEquals("legacy-note-01", note.id)
        assertEquals("Legacy V1 Note", note.title)
        assertEquals(NoteBodyFormat.PLAIN_TEXT, note.format)
        assertTrue(note.checklistItems.isEmpty())
        assertTrue(note.tags.isEmpty())
    }
}
