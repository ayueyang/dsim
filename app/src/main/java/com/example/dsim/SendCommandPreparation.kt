package com.example.dsim

/** Explicit business rejection, not a database/network/programming failure. */
internal class SendCommandRejectedException(message: String) : IllegalArgumentException(message)

internal inline fun requireSendCommand(value: Boolean, message: () -> String = { "Failed requirement." }) {
    if (!value) throw SendCommandRejectedException(message())
}

/** A business rejection is complete only after its existing durable failure response is stored. */
internal suspend fun prepareSendCommand(
    submit: suspend () -> Unit,
    onRejected: suspend (SendCommandRejectedException) -> Unit
) {
    try {
        submit()
    } catch (e: SendCommandRejectedException) {
        onRejected(e)
    }
}
