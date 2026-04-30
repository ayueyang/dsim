package com.example.dsim

import com.example.dsim.database.SmsMessage

data class SyncPayload(
    val sms: SmsMessage,
    val remarkPhone: String,
    val deviceName: String? = null,
    val silentSync: Boolean = false,
    val historyImport: Boolean = false
)
