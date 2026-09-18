package com.example.dsim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the cloud config guards added in batch D.
 * Wildcards in the base topic are the security-relevant case: C13 builds `<base>/<deviceId>` for
 * publishing and `<base>/+` for subscribing, so a `+`/`#` inside the base would widen both.
 */
class CloudSettingsManagerTest {

    private fun valid(raw: String): String {
        val result = CloudSettingsManager.validateBaseTopic(raw)
        assertTrue("expected valid: $raw, got $result", result is CloudSettingsManager.TopicValidation.Valid)
        return (result as CloudSettingsManager.TopicValidation.Valid).normalized
    }

    private fun reason(raw: String): CloudSettingsManager.TopicValidation.Reason {
        val result = CloudSettingsManager.validateBaseTopic(raw)
        assertTrue("expected invalid: $raw, got $result", result is CloudSettingsManager.TopicValidation.Invalid)
        return (result as CloudSettingsManager.TopicValidation.Invalid).reason
    }

    @Test
    fun `accepts ordinary room names`() {
        assertEquals("dsim/my-room", valid("dsim/my-room"))
        assertEquals("room_01", valid("room_01"))
        assertEquals("家里的设备", valid("家里的设备"))
    }

    @Test
    fun `normalizes surrounding space and trailing slashes`() {
        assertEquals("dsim/room", valid("  dsim/room  "))
        assertEquals("dsim/room", valid("dsim/room///"))
    }

    @Test
    fun `rejects single level wildcard`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WILDCARD, reason("dsim/+/room"))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WILDCARD, reason("+"))
    }

    @Test
    fun `rejects multi level wildcard`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WILDCARD, reason("dsim/#"))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WILDCARD, reason("#"))
    }

    @Test
    fun `rejects blank input`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.EMPTY, reason(""))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.EMPTY, reason("   "))
        // "///" is reported as LEADING_SLASH — a more specific message than "empty".
        assertEquals(CloudSettingsManager.TopicValidation.Reason.LEADING_SLASH, reason("///"))
    }

    @Test
    fun `rejects inner whitespace and control characters`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WHITESPACE, reason("dsim room"))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.WHITESPACE, reason("dsim\troom"))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.CONTROL_CHARACTER, reason("dsim\u0000room"))
    }

    @Test
    fun `rejects malformed slashes`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.LEADING_SLASH, reason("/dsim/room"))
        assertEquals(CloudSettingsManager.TopicValidation.Reason.EMPTY_SEGMENT, reason("dsim//room"))
    }

    @Test
    fun `rejects overlong topic`() {
        assertEquals(CloudSettingsManager.TopicValidation.Reason.TOO_LONG, reason("a".repeat(201)))
        assertEquals("a".repeat(200), valid("a".repeat(200)))
    }

    @Test
    fun `validated topic stays exact under CloudTopics`() {
        // The whole point of the guard: publish target must remain a single concrete topic.
        val base = valid("dsim/room")
        val publish = CloudTopics.publishTopic(base, "device-1")
        assertTrue(!publish.contains('+') && !publish.contains('#'))
        assertEquals("dsim/room/device-1", publish)
    }

    @Test
    fun `classifies transport security`() {
        assertEquals(CloudSettingsManager.BrokerKind.SECURE, CloudSettingsManager.classifyBroker("ssl://broker.emqx.io:8883"))
        assertEquals(CloudSettingsManager.BrokerKind.SECURE, CloudSettingsManager.classifyBroker("WSS://example.com:443"))
        assertEquals(CloudSettingsManager.BrokerKind.PLAINTEXT, CloudSettingsManager.classifyBroker("tcp://broker.emqx.io:1883"))
        assertEquals(CloudSettingsManager.BrokerKind.PLAINTEXT, CloudSettingsManager.classifyBroker("ws://example.com:8083"))
        assertEquals(CloudSettingsManager.BrokerKind.UNKNOWN, CloudSettingsManager.classifyBroker("broker.emqx.io"))
        assertEquals(CloudSettingsManager.BrokerKind.UNKNOWN, CloudSettingsManager.classifyBroker(""))
    }

    @Test
    fun `default broker uses tls`() {
        assertEquals(
            CloudSettingsManager.BrokerKind.SECURE,
            CloudSettingsManager.classifyBroker(CloudSettingsManager.DEFAULT_BROKER)
        )
        assertTrue(CloudSettingsManager.DEFAULT_BROKER.endsWith(":8883"))
    }

    @Test
    fun `warns about plaintext and public broker`() {
        assertTrue(CloudConfigMessages.brokerWarning("tcp://broker.emqx.io:1883")!!.contains("ssl://"))
        assertTrue(CloudConfigMessages.brokerWarning("tcp://my-own-host:1883")!!.contains("TLS"))
        assertTrue(CloudConfigMessages.brokerWarning("ssl://broker.emqx.io:8883")!!.contains("公共测试服务器"))
        // A private TLS broker is the good case and must stay silent.
        assertNull(CloudConfigMessages.brokerWarning("ssl://mqtt.example.com:8883"))
    }

    @Test
    fun `every rejection reason has user facing text`() {
        CloudSettingsManager.TopicValidation.Reason.values().forEach { reason ->
            assertTrue(CloudConfigMessages.topicError(reason).isNotBlank())
        }
    }
}
