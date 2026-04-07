package com.example.dsim.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_messages")
data class SmsMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
    val status: Int = 1,
    val isRead: Boolean = false,
    val deviceId: String,
    val simId: Int,
    val iccid: String?,
    val mappingKey: String,
    val errorMsg: String? = null
)

@Entity(tableName = "sim_card_configs")
data class SimCardConfig(
    @PrimaryKey val mappingKey: String,
    val phoneNumber: String,
    val alias: String? = null,
    val bindMode: String,
    val isActive: Boolean = true
)
