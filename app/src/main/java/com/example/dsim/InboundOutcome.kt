package com.example.dsim

import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException

/** Only committed work or a permanent protocol rejection may release MQTT delivery. */
internal enum class InboundOutcome {
    Committed,
    PermanentlyRejected,
    RetryableFailure;

    companion object {
        fun fromFailure(error: Exception): InboundOutcome = when (error) {
            is CancellationException -> throw error
            is JsonSyntaxException -> PermanentlyRejected
            // SQLiteException / IOException and unclassified failures must not lose delivery.
            // Unknown failures can repeat until diagnosed; that is safer than silent data loss.
            else -> RetryableFailure
        }
    }
}
