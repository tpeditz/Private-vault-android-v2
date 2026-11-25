package com.tpeditz.privatevault.v2.data

import java.util.concurrent.ConcurrentHashMap

class InMemoryVaultDao : VaultDao, VaultRepository.TransactionalVaultDao {

    val envelopes = ConcurrentHashMap<Int, VaultEnvelopeEntity>()
    val records = ConcurrentHashMap<String, EncryptedRecordEntity>() // key: "${vaultSlot}_${recordId}"
    val mediaFiles = ConcurrentHashMap<String, EncryptedMediaFileEntity>() // key: "${vaultSlot}_${mediaId}"
    val docFiles = ConcurrentHashMap<String, EncryptedDocumentFileEntity>() // key: "${vaultSlot}_${documentId}"

    override suspend fun <T> withTransaction(block: suspend () -> T): T {
        val snapEnvelopes = HashMap(envelopes)
        val snapRecords = HashMap(records)
        val snapMedia = HashMap(mediaFiles)
        val snapDocs = HashMap(docFiles)
        return try {
            block()
        } catch (t: Throwable) {
            envelopes.clear(); envelopes.putAll(snapEnvelopes)
            records.clear(); records.putAll(snapRecords)
            mediaFiles.clear(); mediaFiles.putAll(snapMedia)
            docFiles.clear(); docFiles.putAll(snapDocs)
            throw t
        }
    }

    override suspend fun getAllEnvelopes(): List<VaultEnvelopeEntity> {
        return envelopes.values.sortedBy { it.slotId }
    }

    override suspend fun getEnvelopeForSlot(slotId: Int): VaultEnvelopeEntity? {
        return envelopes[slotId]
    }

    override suspend fun upsertEnvelope(envelope: VaultEnvelopeEntity) {
        envelopes[envelope.slotId] = envelope
    }

    override suspend fun upsertEnvelopes(envelopesList: List<VaultEnvelopeEntity>) {
        envelopesList.forEach { upsertEnvelope(it) }
    }

    override suspend fun getRecordsForSlot(vaultSlot: Int): List<EncryptedRecordEntity> {
        return records.values.filter { it.vaultSlot == vaultSlot }
    }

    override suspend fun getRecordById(vaultSlot: Int, recordId: String): EncryptedRecordEntity? {
        return records["${vaultSlot}_$recordId"]
    }

    override suspend fun upsertRecord(record: EncryptedRecordEntity) {
        records["${record.vaultSlot}_${record.recordId}"] = record
    }

    override suspend fun upsertRecords(recordsList: List<EncryptedRecordEntity>) {
        recordsList.forEach { upsertRecord(it) }
    }

    override suspend fun deleteRecord(vaultSlot: Int, recordId: String) {
        records.remove("${vaultSlot}_$recordId")
    }

    override suspend fun deleteAllRecordsForSlot(vaultSlot: Int) {
        val toRemove = records.filterValues { it.vaultSlot == vaultSlot }.keys
        toRemove.forEach { records.remove(it) }
    }

    override suspend fun deleteAllRecords() {
        records.clear()
    }

    override suspend fun countRecordsForSlot(vaultSlot: Int): Int {
        return records.values.count { it.vaultSlot == vaultSlot }
    }

    override suspend fun upsertMediaFileEntity(entity: EncryptedMediaFileEntity) {
        mediaFiles["${entity.vaultSlot}_${entity.mediaId}"] = entity
    }

    override suspend fun upsertMediaFileEntities(entities: List<EncryptedMediaFileEntity>) {
        entities.forEach { upsertMediaFileEntity(it) }
    }

    override suspend fun getMediaFileEntity(vaultSlot: Int, mediaId: String): EncryptedMediaFileEntity? {
        return mediaFiles["${vaultSlot}_$mediaId"]
    }

    override suspend fun getAllMediaFileEntitiesForSlot(vaultSlot: Int): List<EncryptedMediaFileEntity> {
        return mediaFiles.values.filter { it.vaultSlot == vaultSlot }
    }

    override suspend fun getAllMediaFileEntities(): List<EncryptedMediaFileEntity> {
        return mediaFiles.values.toList()
    }

    override suspend fun deleteMediaFileEntity(vaultSlot: Int, mediaId: String) {
        mediaFiles.remove("${vaultSlot}_$mediaId")
    }

    override suspend fun deleteAllMediaFilesForSlot(vaultSlot: Int) {
        val toRemove = mediaFiles.filterValues { it.vaultSlot == vaultSlot }.keys
        toRemove.forEach { mediaFiles.remove(it) }
    }

    override suspend fun deleteAllMediaFiles() {
        mediaFiles.clear()
    }

    override suspend fun upsertDocumentFileEntity(entity: EncryptedDocumentFileEntity) {
        docFiles["${entity.vaultSlot}_${entity.documentId}"] = entity
    }

    override suspend fun upsertDocumentFileEntities(entities: List<EncryptedDocumentFileEntity>) {
        entities.forEach { upsertDocumentFileEntity(it) }
    }

    override suspend fun getDocumentFileEntity(vaultSlot: Int, documentId: String): EncryptedDocumentFileEntity? {
        return docFiles["${vaultSlot}_$documentId"]
    }

    override suspend fun getAllDocumentFileEntitiesForSlot(vaultSlot: Int): List<EncryptedDocumentFileEntity> {
        return docFiles.values.filter { it.vaultSlot == vaultSlot }
    }

    override suspend fun getAllDocumentFileEntities(): List<EncryptedDocumentFileEntity> {
        return docFiles.values.toList()
    }

    override suspend fun deleteDocumentFileEntity(vaultSlot: Int, documentId: String) {
        docFiles.remove("${vaultSlot}_$documentId")
    }

    override suspend fun deleteAllDocumentFilesForSlot(vaultSlot: Int) {
        val toRemove = docFiles.filterValues { it.vaultSlot == vaultSlot }.keys
        toRemove.forEach { docFiles.remove(it) }
    }

    override suspend fun deleteAllDocumentFiles() {
        docFiles.clear()
    }
}
