package com.example.dsim

/**
 * Replay protection for the group channel (F9). Every payload carries an envelope `ts` (sender
 * epoch millis) and `nonce`; a receiver rejects anything missing them, outside the freshness
 * window, or already seen from the same sender. Pure Kotlin; the handler owns one instance per
 * process. Not thread-safe by itself - callers serialise (the inbound handler does).
 */
class ReplayGuard(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    enum class Reason { MISSING, STALE, DUPLICATE }

    sealed interface Verdict {
        object Accept : Verdict
        data class Reject(val reason: Reason, val detail: String) : Verdict
    }

    /** nonce key -> first-seen ts. LinkedHashMap in access order gives us LRU eviction for free. */
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    fun check(senderId: String, ts: Long?, nonce: String?, nowMs: Long, windowMs: Long = DEFAULT_WINDOW_MS): Verdict {
        if (ts == null || ts <= 0L || nonce.isNullOrBlank()) {
            return Verdict.Reject(Reason.MISSING, "no ts/nonce")
        }
        val skew = nowMs - ts
        if (skew > windowMs || skew < -windowMs) {
            return Verdict.Reject(Reason.STALE, "skew=${skew}ms window=${windowMs}ms")
        }
        val key = "$senderId|$nonce"
        if (seen.containsKey(key)) {
            return Verdict.Reject(Reason.DUPLICATE, "nonce seen")
        }
        seen[key] = ts
        // Drop entries that can no longer collide with anything acceptable.
        if (seen.size > capacity / 2) {
            val cutoff = nowMs - windowMs
            val it = seen.entries.iterator()
            while (it.hasNext()) if (it.next().value < cutoff) it.remove()
        }
        return Verdict.Accept
    }

    val size: Int get() = seen.size

    companion object {
        /** Ordinary control / sync traffic must arrive within this of the sender's clock. */
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
        /**
         * Last Will is encrypted when the session connects and emitted by the broker whenever
         * the socket dies, possibly hours later; replaying it only flips a presence dot.
         */
        const val OFFLINE_WINDOW_MS = 24 * 60 * 60_000L
        const val DEFAULT_CAPACITY = 4096
    }
}
