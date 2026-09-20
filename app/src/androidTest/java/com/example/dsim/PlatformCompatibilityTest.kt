package com.example.dsim

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Android runtime tests: no SDK_INT override, no mocked telephony or Base64 implementation. */
@RunWith(AndroidJUnit4::class)
class PlatformCompatibilityTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_PHONE_STATE)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nonceAndDsm3SurviveRealQos1Publish() {
        val id = HardwareProbeUtils.getDeviceId(context)
        val json = MqttPayloadCodec.encode(Ping(deviceId = id))
        val envelope = requireNotNull(MqttPayloadCodec.decodeEnvelope(json))
        val nonce = requireNotNull(envelope.nonce)
        assertTrue(nonce.matches(Regex("[A-Za-z0-9_-]{16}")))
        assertEquals(12, Base64.decode(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).size)
        assertNotNull(envelope.ts)
        val secret = UUID.randomUUID().toString()
        val encrypted = requireNotNull(DsimCryptoUtils.encryptOrNull(json, secret))
        val topic = CloudTopics.publishTopic("dsim/test/batcha", id)
        // Isolated one-message MQTT peer on the device; no external broker or saved credentials.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit(Callable {
            server.accept().use { socket ->
                socket.soTimeout = 15000
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                assertEquals(0x10, packet(input).first)
                output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush()
                val (header, body) = packet(input)
                assertEquals("QoS1 PUBLISH", 0x32, header)
                val topicSize = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                val actualTopic = String(body, 2, topicSize, Charsets.UTF_8)
                val pid = 2 + topicSize
                val payload = String(body, pid + 2, body.size - pid - 2, Charsets.UTF_8)
                output.write(byteArrayOf(0x40, 2, body[pid], body[pid + 1])); output.flush()
                assertEquals(0xe0, packet(input).first)
                actualTopic to payload
            }
        })
        val client = MqttClient("tcp://127.0.0.1:${server.localPort}", "dSIM_${UUID.randomUUID()}", MemoryPersistence())
        client.timeToWait = 15000
        try {
            client.connect(MqttConnectOptions().apply { isCleanSession = false; connectionTimeout = 10 })
            client.publish(topic, MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
            client.disconnect()
            val (actualTopic, payload) = received.get(15, TimeUnit.SECONDS)
            assertEquals(topic, actualTopic)
            assertEquals(json, DsimCryptoUtils.decryptMessage(payload, secret))
            assertEquals(nonce, requireNotNull(MqttPayloadCodec.decodeEnvelope(requireNotNull(DsimCryptoUtils.decryptMessage(payload, secret)))).nonce)
            report("NONCE_QOS1_PUBLISH api=${Build.VERSION.SDK_INT} nonceChars=${nonce.length} nonceBytes=12 puback=true DSM3_roundtrip=true")
        } finally {
            runCatching { client.disconnectForcibly(0, 0) }
            runCatching { client.close(true) }
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun slotSimStateFallbackUsesRealTelephony() {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        assertEquals("test AVD must have a ready SIM", TelephonyManager.SIM_STATE_READY, telephony.simState)
        // Active subscriptions normally bypass this fallback. Invoke the production private method
        // explicitly, keeping the real platform manager and API level, rather than claiming coverage.
        val method = HardwareProbeUtils::class.java.getDeclaredMethod("getFallbackSlotBasedSimInfo", TelephonyManager::class.java, String::class.java)
        method.isAccessible = true
        val id = HardwareProbeUtils.getDeviceId(context)
        @Suppress("UNCHECKED_CAST")
        val slots = method.invoke(HardwareProbeUtils, telephony, id) as List<SimHardwareData>
        assertTrue("real fallback must enumerate slot 0", slots.any { it.slotIndex == 0 })
        assertEquals("DEV_${id}_SLOT_0", slots.first { it.slotIndex == 0 }.mappingKey)
        report("SIM_STATE_GUARD api=${Build.VERSION.SDK_INT} branch=${if (Build.VERSION.SDK_INT < 26) "legacy_getSimState()" else "getSimState(slot)"} ready=true slots=${slots.size}")
    }

    @Test fun slotMappingResolvesARealSubscription() {
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val active = manager.activeSubscriptionInfoList.orEmpty()
        assertTrue("test AVD must have an active subscription", active.isNotEmpty())
        val info = active.first { it.simSlotIndex >= 0 }
        val key = HardwareProbeUtils.buildNoRootMappingKey(HardwareProbeUtils.getDeviceId(context), null, info.simSlotIndex)
        val resolved = HardwareProbeUtils.resolveSubscriptionIdForMappingKey(context, key)
        assertEquals(info.subscriptionId, resolved)
        report("SUBSCRIPTION_GUARD api=${Build.VERSION.SDK_INT} branch=${if (Build.VERSION.SDK_INT < 29) "legacy_activeSlotInfo" else "getSubscriptionIds/isValidSubscriptionId"} slot=${info.simSlotIndex} resolved=$resolved")
    }

    private fun packet(input: DataInputStream): Pair<Int, ByteArray> {
        val header = input.readUnsignedByte()
        var remaining = 0
        var multiplier = 1
        do {
            val next = input.readUnsignedByte()
            remaining += (next and 127) * multiplier
            multiplier *= 128
            check(multiplier <= 268435456 && remaining <= 65536)
        } while (next and 128 != 0)
        return header to ByteArray(remaining).also { input.readFully(it) }
    }

    private fun report(text: String) {
        android.util.Log.i("dSIM_BatchA", text)
        // IN_PROGRESS, not a second success event; do not inflate completed-test counts.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", text + "\n") })
    }
}
