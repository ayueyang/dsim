package com.example.dsim

/**
 * Backoff schedule for [MqttSyncService]'s own reconnect loop.
 *
 * The service, not Paho, owns reconnection: Paho's automaticReconnect only kicks in after a
 * successful connect followed by connectionLost, so a failed initial connect (network still
 * coming up, broker unreachable) would never be retried. Pure object; no Android dependencies.
 */
object ReconnectPolicy {
    /** Delay before the first retry. */
    const val BASE_DELAY_MS = 5_000L
    /** Upper bound; a phone that lost its broker for hours must not hammer it, nor sleep forever. */
    const val MAX_DELAY_MS = 5 * 60_000L

    /**
     * Delay before retry number [attempt] (1-based). Doubles from [BASE_DELAY_MS] and saturates at
     * [MAX_DELAY_MS]; non-positive attempts are treated as the first.
     */
    fun delayForAttempt(attempt: Int): Long {
        val n = (attempt - 1).coerceIn(0, 30)
        val raw = BASE_DELAY_MS shl n
        return if (raw <= 0L || raw > MAX_DELAY_MS) MAX_DELAY_MS else raw
    }

    /**
     * Whether the service may (re)connect on its own right now. Mirrors the rules the user sees:
     * cloud enabled, not manually disconnected this session, the 断线自动重连 switch on, and a
     * complete cloud config.
     */
    fun shouldReconnect(
        cloudEnabled: Boolean,
        manualDisconnect: Boolean,
        autoReconnectEnabled: Boolean,
        configComplete: Boolean
    ): Boolean = cloudEnabled && !manualDisconnect && autoReconnectEnabled && configComplete
}
