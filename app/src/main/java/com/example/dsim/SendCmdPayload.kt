package com.example.dsim

data class SendCmdPayload(
    val action: String = "SEND_CMD",
    val target: String,
    val body: String,
    val mappingKey: String,
    val uuid: String
)
