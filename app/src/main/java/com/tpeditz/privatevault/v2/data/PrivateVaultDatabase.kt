package com.tpeditz.privatevault.v2.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VaultEnvelopeEntity::class,
        EncryptedRecordEntity::class,
        EncryptedMediaFileEntity::class,
        EncryptedDocumentFileEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PrivateVaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val DATABASE_NAME = "private_vault_encrypted.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_encrypted_records_vault_slot` ON `encrypted_records` (`vault_slot`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `encrypted_records_new` (
                        `record_id` TEXT NOT NULL,
                        `vault_slot` INTEGER NOT NULL,
                        `schema_version` INTEGER NOT NULL,
                        `nonce` BLOB NOT NULL,
                        `ciphertext` BLOB NOT NULL,
                        PRIMARY KEY(`vault_slot`, `record_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `encrypted_records_new` (`record_id`, `vault_slot`, `schema_version`, `nonce`, `ciphertext`)
                    SELECT `record_id`, `vault_slot`, `schema_version`, `nonce`, `ciphertext` FROM `encrypted_records`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `encrypted_records`")
                db.execSQL("ALTER TABLE `encrypted_records_new` RENAME TO `encrypted_records`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_encrypted_records_vault_slot` ON `encrypted_records` (`vault_slot`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `encrypted_media_files` (
                        `vault_slot` INTEGER NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `format_version` INTEGER NOT NULL,
                        `relative_media_path` TEXT NOT NULL,
                        `relative_thumb_path` TEXT NOT NULL,
                        `encrypted_size_bytes` INTEGER NOT NULL,
                        `chunk_count` INTEGER NOT NULL,
                        `ciphertext_sha256` TEXT NOT NULL,
                        `import_status` INTEGER NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`vault_slot`, `media_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_encrypted_media_files_vault_slot` ON `encrypted_media_files` (`vault_slot`)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `encrypted_document_files` (
                        `vault_slot` INTEGER NOT NULL,
                        `document_id` TEXT NOT NULL,
                        `format_version` INTEGER NOT NULL,
                        `relative_file_path` TEXT NOT NULL,
                        `encrypted_size_bytes` INTEGER NOT NULL,
                        `chunk_count` INTEGER NOT NULL,
                        `ciphertext_sha256` TEXT NOT NULL,
                        `import_status` INTEGER NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`vault_slot`, `document_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_encrypted_document_files_vault_slot` ON `encrypted_document_files` (`vault_slot`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: PrivateVaultDatabase? = null

        fun getInstance(context: Context): PrivateVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PrivateVaultDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Never enable fallbackToDestructiveMigration() so an update can never wipe vaults
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun createInMemoryForTest(context: Context): PrivateVaultDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PrivateVaultDatabase::class.java
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }
    }
}
