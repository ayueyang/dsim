package com.example.dsim

import java.util.Calendar

/**
 * Every carrier segment the executing device sends costs real money (about 0.1 CNY). These are the
 * pure rules behind the two executor-side guards: the "allow other devices to send through me"
 * switch and the per-device daily segment cap. Pure object; no Android dependencies.
 */
object SendCostPolicy {
    /** Segments per local calendar day one device will execute for peers. */
    const val DEFAULT_DAILY_LIMIT = 50
    /** A limit of 0 means "no cap"; the switch is the only guard then. */
    const val UNLIMITED = 0
    const val MAX_LIMIT = 10_000

    /** Start of the local calendar day containing [nowMs]. */
    fun startOfDay(nowMs: Long, calendar: Calendar = Calendar.getInstance()): Long {
        calendar.timeInMillis = nowMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * True when executing a command of [requestedParts] segments would push today's total past
     * [limit]. Reservations/submitted segments in ALL states count, including FAILED: one failed part
     * does not undo successful parts, and even failed callbacks do not prove zero carrier charge.
     */
    fun isOverLimit(segmentsToday: Int, requestedParts: Int, limit: Int): Boolean {
        if (limit <= UNLIMITED) return false
        return segmentsToday + requestedParts.coerceAtLeast(1) > limit
    }

    /** Clamp a user-entered limit; blank / garbage falls back to [DEFAULT_DAILY_LIMIT]. */
    fun sanitizeLimit(raw: String?): Int {
        val n = raw?.trim()?.toIntOrNull() ?: return DEFAULT_DAILY_LIMIT
        return n.coerceIn(UNLIMITED, MAX_LIMIT)
    }

    fun overLimitMessage(limit: Int): String = "对方设备今日代发已达上限（$limit 条），已拒绝"
    const val REMOTE_SEND_DISABLED_MESSAGE = "对方设备已关闭「允许其他设备代发」，已拒绝"
}
