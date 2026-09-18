package com.example.dsim

import com.example.dsim.database.SendCommandRecord
import java.security.MessageDigest

/** Pure decisions shared by dispatcher, callback and tests. Unknown never permits replay. */
object SendCommandPolicy {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
    const val CALLBACK_WAIT_MS = 120_000L

    fun fingerprint(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun status(state: String): Int = when (state) {
        SENT -> 1
        FAILED -> -1
        UNKNOWN -> -2
        else -> 0
    }

    fun recordPart(record: SendCommandRecord, part: Int, success: Boolean, code: Int): SendCommandRecord {
        if (record.state == SENT || record.state == FAILED || part !in 0 until record.partCount) return record
        val completed = record.completedParts.split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
        if (!completed.add(part)) return record
        // UNKNOWN's explanatory text is not a telephony failure. Persist only actual errors.
        val error = if (!success) "短信分段 ${part + 1} 发送失败（系统代码 $code）；其他分段可能已发出"
            else record.errorMsg.takeIf { record.hasFailedPart }
        val state = if (completed.size == record.partCount) {
            if (!record.hasFailedPart && success) SENT else FAILED
        } else PENDING
        return record.copy(completedParts = completed.sorted().joinToString(","), state = state, errorMsg = error, hasFailedPart = record.hasFailedPart || !success)
    }
}
