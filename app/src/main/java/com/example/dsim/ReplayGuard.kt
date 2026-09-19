package com.example.dsim

/**
 * Replay protection for one inbound handler (which can survive multiple MQTT sessions).
 * All messages require ts/nonce. Durable, UUID-idempotent deliveries have no time limit;
 * commands with side effects retain a short TTL. Nonces are bounded by capacity, never age.
 * Not thread-safe: callers must serialize access.
 */
class ReplayGuard(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    enum class Reason { MISSING, STALE, DUPLICATE }

    enum class Policy(val windowMs: Long?) {
        DURABLE(null), SHORT_LIVED(DEFAULT_WINDOW_MS), OFFLINE(OFFLINE_WINDOW_MS);

        companion object {
            fun forMessage(message: MqttInbound): Policy = when (message) {
                is SmsSync, is SendCmdResult, is HistorySyncAckMsg -> DURABLE
                is SendCmd, is Ping, is Pong, is HistoryQueueBatch -> SHORT_LIVED
                is Offline -> OFFLINE
            }
        }
    }

    sealed interface Verdict {
        object Accept : Verdict
        data class Reject(val reason: Reason, val detail: String) : Verdict
    }

    /** Capacity-bounded nonce cache. Durable messages must not lose deduplication with age. */
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    fun check(senderId: String, ts: Long?, nonce: String?, nowMs: Long, policy: Policy): Verdict {
        if (ts == null || ts <= 0L || nonce.isNullOrBlank()) {
            return Verdict.Reject(Reason.MISSING, "no ts/nonce")
        }
        val skew = nowMs - ts
        val windowMs = policy.windowMs
        if (windowMs != null && (skew > windowMs || skew < -windowMs)) {
            return Verdict.Reject(Reason.STALE, "skew=${skew}ms window=${windowMs}ms")
        }
        val key = "$senderId|$nonce"
        if (seen[key] != null) {
            return Verdict.Reject(Reason.DUPLICATE, "nonce seen")
        }
        seen[key] = ts
        return Verdict.Accept
    }

    val size: Int get() = seen.size

    companion object {
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
        /**
         * Last Will is stamped when the session connects, not when the socket dies.
         * 超过 24 h 的 OFFLINE 丢弃无副作用，由下一个 PONG 纠正。
         */
        const val OFFLINE_WINDOW_MS = 24 * 60 * 60_000L
        const val DEFAULT_CAPACITY = 4096
    }
}
