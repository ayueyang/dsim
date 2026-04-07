package com.example.dsim.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SmsMessage::class, SimCardConfig::class], version = 1, exportSchema = false)
abstract class DsimDatabase : RoomDatabase() {
    abstract fun dsimDao(): DsimDao

    companion object {
        @Volatile
        private var INSTANCE: DsimDatabase? = null

        fun getDatabase(context: Context): DsimDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DsimDatabase::class.java,
                    "dsim_core_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
