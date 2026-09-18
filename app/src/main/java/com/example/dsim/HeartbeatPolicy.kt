package com.example.dsim

/**
 * Decides when a device snapshot (PONG) is worth publishing.
 *
 * A snapshot is sent when its fingerprint changed since the last publish, or when the last publish is
 * older than [MAX_SILENCE_MS]. Peers therefore hear from a healthy device at least every
 * [MAX_SILENCE_MS]; [DeviceDirectoryManager.ONLINE_TIMEOUT_MS] must stay comfortably above that.
 * Pure object; no Android dependencies.
 */
object HeartbeatPolicy {
    /** Timer period. Cheap: a tick that decides "no change" costs no network and no crypto. */
    const val TICK_MS = 30_000L
    /** Upper bound on silence for a connected device. */
    const val MAX_SILENCE_MS = 120_000L
    private const val BATTERY_BUCKET = 5

    data class State(val lastFingerprint: String? = null, val lastPublishedAt: Long = 0L)

    /**
     * Fingerprint of everything peers actually render. Battery is bucketed so a 1 % drift does not
     * wake the radio; queue `updatedAt` is excluded because it changes on every local refresh.
     */
    fun fingerprint(
        deviceName: String,
        batteryLevel: Int,
        isCharging: Boolean,
        isDefaultSms: Boolean,
        simSummary: String,
        queueStatus: String,
        queuePosition: Int?,
        queueProgressCurrent: Int,
        queueProgressTotal: Int,
        allowsRemoteStart: Boolean
    ): String {
        val bucket = if (batteryLevel < 0) -1 else (batteryLevel / BATTERY_BUCKET) * BATTERY_BUCKET
        return listOf(
            deviceName, bucket, isCharging, isDefaultSms, simSummary,
            queueStatus, queuePosition ?: -1, queueProgressCurrent, queueProgressTotal, allowsRemoteStart
        ).joinToString("|")
    }

    fun shouldPublish(state: State, fingerprint: String, now: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (state.lastFingerprint != fingerprint) return true
        return now - state.lastPublishedAt >= MAX_SILENCE_MS
    }

    fun afterPublish(fingerprint: String, now: Long): State = State(fingerprint, now)
}
