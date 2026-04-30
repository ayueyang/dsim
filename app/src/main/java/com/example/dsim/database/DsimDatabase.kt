package com.example.dsim.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SmsMessage::class,
        SimCardConfig::class,
        DeviceProfile::class,
        DeviceHistoryRecord::class
    ],
    version = 4,
    exportSchema = false
)
abstract class DsimDatabase : RoomDatabase() {
    abstract fun dsimDao(): DsimDao

    companion object {
        @Volatile
        private var INSTANCE: DsimDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_profiles` (
                        `deviceId` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `phoneNumbers` TEXT NOT NULL,
                        `batteryLevel` INTEGER NOT NULL,
                        `isCharging` INTEGER NOT NULL,
                        `isDefaultSms` INTEGER NOT NULL,
                        `simCount` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `isLocalDevice` INTEGER NOT NULL,
                        `firstSeenAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `phoneNumbers` TEXT NOT NULL,
                        `batteryLevel` INTEGER NOT NULL,
                        `isCharging` INTEGER NOT NULL,
                        `isDefaultSms` INTEGER NOT NULL,
                        `simCount` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `seenAt` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_device_history_deviceId_seenAt` ON `device_history` (`deviceId`, `seenAt`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `sim_card_configs` ADD COLUMN `deviceId` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `sim_card_configs` ADD COLUMN `subscriptionId` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `sim_card_configs` ADD COLUMN `slotIndex` INTEGER"
                )
                database.execSQL(
                    """
                    UPDATE `sim_card_configs`
                    SET `deviceId` = CASE
                        WHEN instr(`mappingKey`, '_SUBID_') > 0 THEN substr(`mappingKey`, 5, instr(`mappingKey`, '_SUBID_') - 5)
                        WHEN instr(`mappingKey`, '_SLOT_') > 0 THEN substr(`mappingKey`, 5, instr(`mappingKey`, '_SLOT_') - 5)
                        ELSE ''
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE `sim_card_configs`
                    SET `subscriptionId` = CASE
                        WHEN instr(`mappingKey`, '_SUBID_') > 0 THEN CAST(substr(`mappingKey`, instr(`mappingKey`, '_SUBID_') + 7) AS INTEGER)
                        ELSE NULL
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE `sim_card_configs`
                    SET `slotIndex` = CASE
                        WHEN instr(`mappingKey`, '_SLOT_') > 0 THEN CAST(substr(`mappingKey`, instr(`mappingKey`, '_SLOT_') + 6) AS INTEGER)
                        ELSE NULL
                    END
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_sim_card_configs_deviceId_subscriptionId`
                    ON `sim_card_configs` (`deviceId`, `subscriptionId`)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_sim_card_configs_deviceId_slotIndex`
                    ON `sim_card_configs` (`deviceId`, `slotIndex`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `allowsRemoteHistorySync` INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueId` TEXT"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueStatus` TEXT NOT NULL DEFAULT 'IDLE'"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueuePosition` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueLabel` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueDetail` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueProgressCurrent` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueProgressTotal` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `device_profiles` ADD COLUMN `historyQueueUpdatedAt` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): DsimDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DsimDatabase::class.java,
                    "dsim_core_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
