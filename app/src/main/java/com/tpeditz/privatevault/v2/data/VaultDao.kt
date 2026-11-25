package com.tpeditz.privatevault.v2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_envelopes ORDER BY slot_id ASC")
    suspend fun getAllEnvelopes(): List<VaultEnvelopeEntity>

    @Query("SELECT * FROM vault_envelopes WHERE slot_id = :slotId LIMIT 1")
    suspend fun getEnvelopeForSlot(slotId: Int): VaultEnvelopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEnvelope(envelope: VaultEnvelopeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEnvelopes(envelopes: List<VaultEnvelopeEntity>)

    @Query("SELECT * FROM encrypted_records WHERE vault_slot = :vaultSlot")
    suspend fun getRecordsForSlot(vaultSlot: Int): List<EncryptedRecordEntity>

    @Query("SELECT * FROM encrypted_records WHERE vault_slot = :vaultSlot AND record_id = :recordId LIMIT 1")
    suspend fun getRecordById(vaultSlot: Int, recordId: String): EncryptedRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: EncryptedRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecords(records: List<EncryptedRecordEntity>)

    @Query("DELETE FROM encrypted_records WHERE vault_slot = :vaultSlot AND record_id = :recordId")
    suspend fun deleteRecord(vaultSlot: Int, recordId: String)

    @Query("DELETE FROM encrypted_records WHERE vault_slot = :vaultSlot")
    suspend fun deleteAllRecordsForSlot(vaultSlot: Int)

    @Query("DELETE FROM encrypted_records")
    suspend fun deleteAllRecords()

    @Query("SELECT COUNT(*) FROM encrypted_records WHERE vault_slot = :vaultSlot")
    suspend fun countRecordsForSlot(vaultSlot: Int): Int

    // ============================================================================================
    // Encrypted Media Files (`encrypted_media_files`)
    // ============================================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaFileEntity(entity: EncryptedMediaFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaFileEntities(entities: List<EncryptedMediaFileEntity>)

    @Query("SELECT * FROM encrypted_media_files WHERE vault_slot = :vaultSlot AND media_id = :mediaId LIMIT 1")
    suspend fun getMediaFileEntity(vaultSlot: Int, mediaId: String): EncryptedMediaFileEntity?

    @Query("SELECT * FROM encrypted_media_files WHERE vault_slot = :vaultSlot")
    suspend fun getAllMediaFileEntitiesForSlot(vaultSlot: Int): List<EncryptedMediaFileEntity>

    @Query("SELECT * FROM encrypted_media_files")
    suspend fun getAllMediaFileEntities(): List<EncryptedMediaFileEntity>

    @Query("DELETE FROM encrypted_media_files WHERE vault_slot = :vaultSlot AND media_id = :mediaId")
    suspend fun deleteMediaFileEntity(vaultSlot: Int, mediaId: String)

    @Query("DELETE FROM encrypted_media_files WHERE vault_slot = :vaultSlot")
    suspend fun deleteAllMediaFilesForSlot(vaultSlot: Int)

    @Query("DELETE FROM encrypted_media_files")
    suspend fun deleteAllMediaFiles()

    // ============================================================================================
    // Encrypted Document Files (`encrypted_document_files`)
    // ============================================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocumentFileEntity(entity: EncryptedDocumentFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocumentFileEntities(entities: List<EncryptedDocumentFileEntity>)

    @Query("SELECT * FROM encrypted_document_files WHERE vault_slot = :vaultSlot AND document_id = :documentId LIMIT 1")
    suspend fun getDocumentFileEntity(vaultSlot: Int, documentId: String): EncryptedDocumentFileEntity?

    @Query("SELECT * FROM encrypted_document_files WHERE vault_slot = :vaultSlot")
    suspend fun getAllDocumentFileEntitiesForSlot(vaultSlot: Int): List<EncryptedDocumentFileEntity>

    @Query("SELECT * FROM encrypted_document_files")
    suspend fun getAllDocumentFileEntities(): List<EncryptedDocumentFileEntity>

    @Query("DELETE FROM encrypted_document_files WHERE vault_slot = :vaultSlot AND document_id = :documentId")
    suspend fun deleteDocumentFileEntity(vaultSlot: Int, documentId: String)

    @Query("DELETE FROM encrypted_document_files WHERE vault_slot = :vaultSlot")
    suspend fun deleteAllDocumentFilesForSlot(vaultSlot: Int)

    @Query("DELETE FROM encrypted_document_files")
    suspend fun deleteAllDocumentFiles()

    // ============================================================================================
    // Transactions
    // ============================================================================================

    @Transaction
    suspend fun commitVerifiedMediaAtomically(
        encryptedRecord: EncryptedRecordEntity,
        committedMediaEntity: EncryptedMediaFileEntity
    ) {
        upsertRecord(encryptedRecord)
        upsertMediaFileEntity(committedMediaEntity)
    }

    @Transaction
    suspend fun commitVerifiedDocumentAtomically(
        encryptedRecord: EncryptedRecordEntity,
        committedDocumentEntity: EncryptedDocumentFileEntity
    ) {
        upsertRecord(encryptedRecord)
        upsertDocumentFileEntity(committedDocumentEntity)
    }

    @Transaction
    suspend fun deleteMediaRecordAndEntityAtomically(vaultSlot: Int, mediaId: String) {
        deleteRecord(vaultSlot, mediaId)
        deleteMediaFileEntity(vaultSlot, mediaId)
    }

    @Transaction
    suspend fun deleteDocumentRecordAndEntityAtomically(vaultSlot: Int, documentId: String) {
        deleteRecord(vaultSlot, documentId)
        deleteDocumentFileEntity(vaultSlot, documentId)
    }

    @Transaction
    suspend fun replaceAllRecordsForSlot(vaultSlot: Int, newRecords: List<EncryptedRecordEntity>) {
        deleteAllRecordsForSlot(vaultSlot)
        if (newRecords.isNotEmpty()) {
            upsertRecords(newRecords)
        }
    }

    @Transaction
    suspend fun replaceAllRecordsAndFilesForSlot(
        vaultSlot: Int,
        newRecords: List<EncryptedRecordEntity>,
        newMediaEntities: List<EncryptedMediaFileEntity>,
        newDocumentEntities: List<EncryptedDocumentFileEntity>
    ) {
        deleteAllRecordsForSlot(vaultSlot)
        deleteAllMediaFilesForSlot(vaultSlot)
        deleteAllDocumentFilesForSlot(vaultSlot)
        if (newRecords.isNotEmpty()) {
            upsertRecords(newRecords)
        }
        if (newMediaEntities.isNotEmpty()) {
            upsertMediaFileEntities(newMediaEntities)
        }
        if (newDocumentEntities.isNotEmpty()) {
            upsertDocumentFileEntities(newDocumentEntities)
        }
    }

    @Transaction
    suspend fun initializeVaultAtomically(
        envelopes: List<VaultEnvelopeEntity>,
        initialRecords: List<EncryptedRecordEntity>
    ) {
        // Setup is creation only, never a reset operation.
        check(getEnvelopeForSlot(0) == null && getEnvelopeForSlot(1) == null) { "A vault already exists." }
        upsertEnvelopes(envelopes)
        if (initialRecords.isNotEmpty()) {
            upsertRecords(initialRecords)
        }
    }

    @Transaction
    suspend fun provisionOrResetDecoyAtomically(
        updatedRealEnvelope: VaultEnvelopeEntity,
        updatedDecoyEnvelope: VaultEnvelopeEntity,
        decoySlotId: Int,
        seedRecords: List<EncryptedRecordEntity>
    ) {
        upsertEnvelopes(listOf(updatedRealEnvelope, updatedDecoyEnvelope))
        deleteAllRecordsForSlot(decoySlotId)
        deleteAllMediaFilesForSlot(decoySlotId)
        deleteAllDocumentFilesForSlot(decoySlotId)
        if (seedRecords.isNotEmpty()) {
            upsertRecords(seedRecords)
        }
    }

    @Transaction
    suspend fun disableDecoyAtomically(
        updatedRealEnvelope: VaultEnvelopeEntity,
        dummyDecoyEnvelope: VaultEnvelopeEntity,
        decoySlotId: Int
    ) {
        upsertEnvelopes(listOf(updatedRealEnvelope, dummyDecoyEnvelope))
        deleteAllRecordsForSlot(decoySlotId)
        deleteAllMediaFilesForSlot(decoySlotId)
        deleteAllDocumentFilesForSlot(decoySlotId)
    }

    @Transaction
    suspend fun rekeySlotAtomically(
        slotId: Int,
        updatedEnvelope: VaultEnvelopeEntity,
        reEncryptedRecords: List<EncryptedRecordEntity>
    ) {
        upsertEnvelope(updatedEnvelope)
        replaceAllRecordsForSlot(slotId, reEncryptedRecords)
    }
}
