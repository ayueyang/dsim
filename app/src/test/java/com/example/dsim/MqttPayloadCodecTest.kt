package com.example.dsim

import com.example.dsim.database.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPayloadCodecTest {

    @Test fun sendCmdRoundTrip() {
        val cmd = SendCmd(target = "10086", body = "hi", mappingKey = "ICCID_1", uuid = "u1", deviceId = "A", deviceName = "phone-A")
        val json = MqttPayloadCodec.encode(cmd)
        assertTrue(json.contains("\"action\":\"SEND_CMD\""))
        assertEquals(cmd, MqttPayloadCodec.decode(json))
    }

    @Test fun sendCmdResultRoundTripAndLegacyWithoutState() {
        val r = SendCmdResult(uuid = "u1", targetDeviceId = "A", deviceId = "B", success = true, state = "SENT", message = null, timestamp = 5L)
        val decoded = MqttPayloadCodec.decode(MqttPayloadCodec.encode(r)) as SendCmdResult
        assertEquals(r, decoded)
        // Older builds omit `state`; decoding must still succeed with a null state.
        val legacy = """{"action":"SEND_CMD_RESULT","uuid":"u2","targetDeviceId":"A","deviceId":"B","success":false,"message":"boom"}"""
        val l = MqttPayloadCodec.decode(legacy) as SendCmdResult
        assertNull(l.state)
        assertEquals("boom", l.message)
    }

    @Test fun pongRoundTripWithNullablePositionAndSimInts() {
        val pong = Pong(
            deviceId = "B", deviceName = "phone-B", battery = 77, isCharging = true, isDefaultSms = false,
            sims = listOf(SimSnapshotMsg(mappingKey = "ICCID_9", deviceId = "B", subscriptionId = null, slotIndex = 0, phone = "138", mode = "ROOT")),
            historyQueue = HistoryQueueStateMsg(allowRemoteStart = false, queueId = "q", status = "IDLE", position = null, updatedAt = 9L)
        )
        val json = MqttPayloadCodec.encode(pong)
        val decoded = MqttPayloadCodec.decode(json) as Pong
        assertEquals(pong, decoded)
        assertNull(decoded.sims[0].subscriptionId)
        assertNull(decoded.historyQueue?.position)
    }

    @Test fun legacyPongWithJsonNullPositionIsAccepted() {
        // Pre-W5 builds emitted JSONObject.NULL for position and included every key.
        val legacy = """{"action":"PONG","deviceId":"B","deviceName":"x","battery":50,"isCharging":false,"isDefaultSms":true,
            "sims":[{"mappingKey":"ICCID_1","deviceId":"B","subscriptionId":1,"slotIndex":0,"phone":"139","mode":"ROOT"}],
            "historyQueue":{"allowRemoteStart":true,"queueId":"","status":"IDLE","position":null,"label":"","detail":"",
            "progressCurrent":0,"progressTotal":0,"updatedAt":0}}"""
        val p = MqttPayloadCodec.decode(legacy) as Pong
        assertEquals(1, p.sims.size)
        assertEquals(1, p.sims[0].subscriptionId)
        assertNull(p.historyQueue?.position)
    }

    @Test fun historyQueueBatchAndAckRoundTrip() {
        val batch = HistoryQueueBatch(queueId = "q1", createdAt = 1L, requestedByDeviceId = "A", requestedByDeviceName = "a",
            targets = listOf(QueueTargetMsg("B", "b", 1), QueueTargetMsg("C", "c", 2)))
        assertEquals(batch, MqttPayloadCodec.decode(MqttPayloadCodec.encode(batch)))
        val ack = HistorySyncAckMsg(uuid = "u", targetDeviceId = "A", deviceId = "B", deviceName = "b", success = true, message = "already_exists")
        assertEquals(ack, MqttPayloadCodec.decode(MqttPayloadCodec.encode(ack)))
    }

    @Test fun pingAndOfflineRoundTrip() {
        assertEquals(Ping(deviceId = "A"), MqttPayloadCodec.decode(MqttPayloadCodec.encode(Ping(deviceId = "A"))))
        val off = Offline(deviceId = "A", timestamp = 42L)
        assertEquals(off, MqttPayloadCodec.decode(MqttPayloadCodec.encode(off)))
    }

    @Test fun smsSyncIsRecognisedWithoutAction() {
        val sms = SmsMessage(uuid = "u", address = "10086", body = "b", timestamp = 1L, type = 1, deviceId = "A", simId = 1, iccid = null, mappingKey = "ICCID_1")
        val payload = SyncPayload(sms = sms, remarkPhone = "138", deviceName = "a", silentSync = true, historyImport = true)
        val decoded = MqttPayloadCodec.decode(MqttPayloadCodec.encode(payload))
        assertTrue(decoded is SmsSync)
        assertEquals(payload, (decoded as SmsSync).payload)
        assertEquals("A", MqttPayloadCodec.senderId(decoded))
    }

    @Test fun garbageAndUnknownActionsDecodeToNull() {
        assertNull(MqttPayloadCodec.decode("not json"))
        assertNull(MqttPayloadCodec.decode("[1,2,3]"))
        assertNull(MqttPayloadCodec.decode("""{"action":"WHATEVER","deviceId":"A"}"""))
        assertNull(MqttPayloadCodec.decode("""{"deviceId":"A"}"""))
        assertNull(MqttPayloadCodec.decode("""{"sms":null}"""))
        // Wrong type for a typed field must not throw.
        assertNull(MqttPayloadCodec.decode("""{"action":"PONG","battery":"lots"}"""))
    }

    @Test fun senderIdPerType() {
        assertEquals("R", MqttPayloadCodec.senderId(HistoryQueueBatch(requestedByDeviceId = "R")))
        assertEquals("X", MqttPayloadCodec.senderId(SendCmdResult(deviceId = "X")))
        assertEquals("", MqttPayloadCodec.senderId(Ping()))
    }

    @Test fun encodeOmitsNulls() {
        val json = MqttPayloadCodec.encode(SendCmdResult(uuid = "u", state = null, message = null))
        assertTrue(!json.contains("\"state\""))
        assertTrue(!json.contains("\"message\""))
    }

    @Test fun encodeStampsEnvelopeAndDecodeEnvelopeReadsIt() {
        val before = System.currentTimeMillis()
        val json = MqttPayloadCodec.encode(Ping(deviceId = "A"))
        val env = MqttPayloadCodec.decodeEnvelope(json)!!
        assertTrue(env.inbound is Ping)
        assertTrue(env.ts!! >= before && env.ts!! <= System.currentTimeMillis())
        assertTrue(env.nonce!!.matches(Regex("[A-Za-z0-9_-]{16}")))
        // two encodes of the same message never share a nonce
        val json2 = MqttPayloadCodec.encode(Ping(deviceId = "A"))
        assertTrue(MqttPayloadCodec.decodeEnvelope(json2)!!.nonce != env.nonce)
    }

    @Test fun stampReplacesExistingEnvelopeAndLeavesPayloadIntact() {
        val original = MqttPayloadCodec.encode(SendCmd(uuid = "u1", target = "10086", body = "hi", mappingKey = "k", deviceId = "A"))
        val first = MqttPayloadCodec.decodeEnvelope(original)!!
        val restamped = MqttPayloadCodec.stamp(original, nowMs = first.ts!! + 3_600_000L)
        val second = MqttPayloadCodec.decodeEnvelope(restamped)!!
        assertEquals(first.ts!! + 3_600_000L, second.ts)
        assertTrue(second.nonce != first.nonce)
        assertEquals(first.inbound, second.inbound)
        // exactly one ts / one nonce key in the output
        assertEquals(1, Regex("\"ts\"").findAll(restamped).count())
        assertEquals(1, Regex("\"nonce\"").findAll(restamped).count())
    }

    @Test fun legacyPayloadWithoutEnvelopeDecodesWithNullEnvelope() {
        val env = MqttPayloadCodec.decodeEnvelope("""{"action":"PING","deviceId":"A"}""")!!
        assertTrue(env.inbound is Ping)
        assertEquals(null, env.ts)
        assertEquals(null, env.nonce)
    }

    @Test fun stampLeavesNonObjectInputAlone() {
        assertEquals("not json", MqttPayloadCodec.stamp("not json"))
        assertEquals("[1,2]", MqttPayloadCodec.stamp("[1,2]"))
    }
}
