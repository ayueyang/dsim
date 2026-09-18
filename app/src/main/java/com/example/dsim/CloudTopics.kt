package com.example.dsim

/**
 * Topic layout for one sync group.
 *
 * ```
 * base            user-entered group topic, e.g. dsim/home
 * base/<deviceId> every device publishes ONLY here
 * base/+          every device subscribes here (QoS 1)
 * ```
 *
 * Because the publisher is encoded in the topic, a device can drop its own echo by string
 * comparison before spending a decrypt on it. Nothing is ever published to `base` itself.
 */
object CloudTopics {
    fun normalizeBase(base: String): String = base.trim().trimEnd('/')

    fun publishTopic(base: String, deviceId: String): String = "${normalizeBase(base)}/$deviceId"

    fun subscriptionFilter(base: String): String = "${normalizeBase(base)}/+"

    /** deviceId encoded in an incoming topic, or null if the topic is not under [base]. */
    fun senderOf(base: String, incomingTopic: String?): String? {
        if (incomingTopic == null) return null
        val prefix = normalizeBase(base) + "/"
        if (!incomingTopic.startsWith(prefix)) return null
        val tail = incomingTopic.substring(prefix.length)
        return tail.takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    fun isOwnEcho(base: String, incomingTopic: String?, localDeviceId: String): Boolean =
        senderOf(base, incomingTopic) == localDeviceId
}
