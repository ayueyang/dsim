package com.example.dsim

import android.database.sqlite.SQLiteException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Only committed work or a permanent protocol rejection may release MQTT delivery. */
internal enum class InboundOutcome {
    Committed,
    PermanentlyRejected,
    RetryableFailure;

    companion object {
        fun fromFailure(error: Exception): InboundOutcome = when (error) {
            is CancellationException -> throw error
            is JsonSyntaxException -> PermanentlyRejected
            is SQLiteException, is IOException -> RetryableFailure
            // Unknown failures can repeat until diagnosed; that is safer than silent data loss.
            else -> RetryableFailure
        }
    }
}
