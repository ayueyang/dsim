package com.example.dsim

import com.example.dsim.database.SmsMessage

data class SyncPayload(
    val sms: SmsMessage,
    val remarkPhone: String
)
