package com.example.dsim

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExpiredSendCommandTest {
    private class Fixture {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = "f4-" + UUID.randomUUID()
        val prefsName = tag + "_prefs"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(if (name == "dSIM_UI_PREFS") prefsName else name, mode)
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("isolated fixture: no automatic broker connection")
            override fun startService(service: Intent): ComponentName? =
                throw IllegalStateException("isolated fixture: no automatic broker connection")
        }
        val cloud = CloudSettingsManager.CloudConfig(CloudSettingsManager.DEFAULT_BROKER, "dsim/test/" + tag, UUID.randomUUID().toString())
        init {
            CloudSettingsManager.saveConfig(context, cloud.broker, cloud.topic, cloud.password)
            UsageModeManager.setMode(context, UsageMode.BIDIRECTIONAL_SYNC)
        }
        val session = CloudSession().apply { broker = cloud.broker; topic = cloud.topic; password = cloud.password }
        val db = DsimDatabase.getDatabase(context)
        val dao = db.dsimDao()
        val localId = HardwareProbeUtils.getDeviceId(context)
        val local = SimCardConfig("card-" + tag, "+15550000001", bindMode = "NO_ROOT", deviceId = localId)
        val publisher = MqttPublisher(context, session) { null }
        val handler = MqttInboundHandler(context, session, publisher)
        fun command(uuid: String = tag) = SendCmd(uuid = uuid, deviceId = "fixture-peer", mappingKey = local.mappingKey,
            target = "10086", body = "expired fixture")
        fun wire(message: Any, stale: Boolean = true): String {
            val json = MqttPayloadCodec.stamp(MqttPayloadCodec.encode(message),
                System.currentTimeMillis() - if (stale) ReplayGuard.DEFAULT_WINDOW_MS + 60000L else 0L)
            return requireNotNull(DsimCryptoUtils.encryptOrNull(json, cloud.password))
        }
        fun key(uuid: String = tag) = SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, uuid, "expired")
        fun consumedNonces(): Int {
            val gate = MqttInboundHandler::class.java.getDeclaredField("commitGate").apply { isAccessible = true }.get(handler)
            return (InboundCommitGate::class.java.getDeclaredField("guard").apply { isAccessible = true }.get(gate) as ReplayGuard).size
        }
        suspend fun cleanup() {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_expired_outbox")
            db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox WHERE uuid LIKE ?", arrayOf("%:" + tag + "%"))
            db.openHelper.writableDatabase.execSQL("DELETE FROM send_commands WHERE uuid LIKE ?", arrayOf(tag + "%"))
            db.openHelper.writableDatabase.execSQL("DELETE FROM sms_messages WHERE uuid LIKE ?", arrayOf(tag + "%"))
            dao.deleteSimConfigByKey(local.mappingKey)
            dao.deleteSimConfigByKey("remote-" + tag)
            base.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun fixture(block: suspend (Fixture) -> Unit): Unit = runBlocking {
        val f = Fixture(); f.dao.saveSimConfig(f.local)
        try { block(f) } finally { f.cleanup() }
    }

    @Test fun staleCommandQueuesOneExplicitFailedResultWithoutClaiming() = fixture { f ->
        val wire = f.wire(f.command())
        repeat(2) { assertEquals(InboundOutcome.PermanentlyRejected, f.handler.handleIncomingMessage(wire, "fixture-peer")) }
        val rows = f.dao.nextOutboxBatch(100).filter { it.uuid == f.key() }
        assertEquals(1, rows.size)
        val encrypted = requireNotNull(DsimCryptoUtils.encryptOrNull(rows.single().payloadJson, f.cloud.password))
        val result = MqttPayloadCodec.decode(requireNotNull(DsimCryptoUtils.decryptMessage(encrypted, f.cloud.password))) as SendCmdResult
        assertEquals(f.tag, result.uuid); assertEquals("fixture-peer", result.targetDeviceId)
        assertEquals(f.localId, result.deviceId); assertEquals("FAILED", result.state)
        assertEquals("已过期未执行", result.message); assertFalse(result.success)
        assertNull(f.dao.getSendCommand(f.tag)); assertEquals(0, f.consumedNonces())
    }

    @Test fun everyExistingLedgerStatePreventsAnExpiredOverride() = fixture { f ->
        for (state in listOf("PENDING", "UNKNOWN", "SENT", "FAILED")) {
            val uuid = f.tag + "-" + state
            val r = SendCommandRecord(uuid, "original", "group", "fixture-peer", "10086", "fixture",
                f.local.mappingKey, f.localId, 1, "", System.currentTimeMillis(), 1, state = state)
            f.dao.claimSendCommand(r)
            assertEquals(InboundOutcome.PermanentlyRejected, f.handler.handleIncomingMessage(f.wire(f.command(uuid)), "fixture-peer"))
            assertNull(f.dao.getOutboxByUuid(f.key(uuid))); assertEquals(r, f.dao.getSendCommand(uuid))
        }
    }

    @Test fun invalidEnvelopeIdentityModeOrGroupNeverCreatesExpiredReceipt() = fixture { f ->
        suspend fun rejected(wire: String, sender: String? = "fixture-peer") {
            f.handler.handleIncomingMessage(wire, sender)
            assertNull(f.dao.getOutboxByUuid(f.key()))
        }
        rejected(f.wire(f.command()), "different-topic-sender"); rejected(f.wire(f.command()), null)
        rejected(f.wire(f.command().copy(uuid = ""))); rejected(f.wire(f.command().copy(deviceId = "")))
        rejected(f.wire(Ping(deviceId = "fixture-peer")))
        val missing = JsonParser.parseString(MqttPayloadCodec.encode(f.command())).asJsonObject.apply { remove("ts") }
        rejected(requireNotNull(DsimCryptoUtils.encryptOrNull(missing.toString(), f.cloud.password)))
        f.dao.saveSimConfig(f.local.copy(bindMode = "REMOTE_SHADOW"))
        val duplicate = f.wire(f.command(), stale = false)
        assertEquals(InboundOutcome.Committed, f.handler.handleIncomingMessage(duplicate, "fixture-peer"))
        rejected(duplicate); rejected(f.wire(f.command()))
        f.dao.saveSimConfig(f.local)
        UsageModeManager.setMode(f.context, UsageMode.LOCAL_ONLY); rejected(f.wire(f.command()))
        UsageModeManager.setMode(f.context, UsageMode.BIDIRECTIONAL_SYNC)
        CloudSettingsManager.saveConfig(f.context, f.cloud.broker, f.cloud.topic + "-changed", f.cloud.password)
        rejected(f.wire(f.command()))
        f.session.topic = ""; rejected(f.wire(f.command()))
    }

    @Test fun failedExpiredEnqueueDoesNotAckAndRetryCommits(): Unit = runBlocking {
        fixture { f ->
            f.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_expired_outbox
                BEFORE INSERT ON sync_outbox WHEN NEW.uuid = '${f.key()}'
                BEGIN SELECT RAISE(ABORT, 'injected expired enqueue failure'); END""")
            val acks = mutableListOf<Int>()
            val dispatcher = InboundDispatcher(this, "fixture", f.localId,
                { body, sender -> f.handler.handleIncomingMessage(body, sender) }, { id, _ -> acks.add(id) })
            val wire = f.wire(f.command())
            dispatcher.onMessage("fixture/fixture-peer", wire, 7, 1, false)!!.join()
            assertTrue("failed durable rejection must not ACK", acks.isEmpty())
            assertNull(f.dao.getOutboxByUuid(f.key())); assertEquals(0, f.consumedNonces())
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_expired_outbox")
            dispatcher.onMessage("fixture/fixture-peer", wire, 7, 1, true)!!.join()
            assertEquals(listOf(7), acks); assertNotNull(f.dao.getOutboxByUuid(f.key()))
            assertEquals(0, f.consumedNonces())
        }
    }

    @Test fun offlineExpiredReceiptDrainsOnceThroughRealQosOne() = fixture { f ->
        f.handler.handleIncomingMessage(f.wire(f.command()), "fixture-peer")
        assertNotNull(f.dao.getOutboxByUuid(f.key()))
        val offline = SyncOutbox.flush(f.context, null, f.cloud)
        assertEquals(1, offline.remaining); assertEquals(1, offline.failed)
        val observed = CompletableFuture<SendCmdResult>(); val server = ServerSocket(0)
        fun packet(input: InputStream): Pair<Int, ByteArray> {
            val type = input.read(); check(type >= 0)
            var length = 0; var multiplier = 1
            do { val digit = input.read(); check(digit >= 0); length += (digit and 127) * multiplier; multiplier *= 128 } while (digit and 128 != 0)
            val data = ByteArray(length); var offset = 0
            while (offset < length) { val n = input.read(data, offset, length - offset); check(n > 0); offset += n }
            return type to data
        }
        Thread {
            try {
                server.soTimeout = 15000
                server.accept().use { socket ->
                    socket.soTimeout = 15000
                    val input = socket.getInputStream(); val output = socket.getOutputStream()
                    check(packet(input).first == 0x10)
                    output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush()
                    val (header, data) = packet(input); check(header == 0x32)
                    val topicLength = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                    val idOffset = 2 + topicLength
                    val result = MqttPayloadCodec.decode(requireNotNull(DsimCryptoUtils.decryptMessage(
                        String(data.copyOfRange(idOffset + 2, data.size), Charsets.UTF_8), f.cloud.password))) as SendCmdResult
                    output.write(byteArrayOf(0x40, 2, data[idOffset], data[idOffset + 1])); output.flush()
                    check(packet(input).first == 0xe0)
                    observed.complete(result)
                }
            } catch (e: Throwable) { observed.completeExceptionally(e) }
        }.start()
        val client = MqttClient("tcp://127.0.0.1:" + server.localPort, "f4-test-" + UUID.randomUUID(), MemoryPersistence())
        try {
            client.timeToWait = 15000
            client.connect(MqttConnectOptions().apply { isCleanSession = false; connectionTimeout = 10 })
            assertEquals(1, SyncOutbox.flush(f.context, client, f.cloud).sent)
            assertEquals(0, SyncOutbox.flush(f.context, client, f.cloud).sent)
            assertNull(f.dao.getOutboxByUuid(f.key())); client.disconnect()
            val result = observed.get(15, TimeUnit.SECONDS)
            assertEquals("FAILED", result.state); assertEquals("fixture-peer", result.targetDeviceId)
        } finally { client.close(); server.close() }
    }

    @Test fun requesterPendingBubbleConvergesOnlyForItsExpectedExecutor() = fixture { f ->
        val remote = f.local.copy(mappingKey = "remote-" + f.tag, bindMode = "REMOTE_SHADOW", deviceId = "executor-peer")
        f.dao.saveSimConfig(remote)
        f.dao.insertMessage(SmsMessage(uuid = f.tag, address = "10086", body = "pending fixture",
            timestamp = System.currentTimeMillis(), type = 2, status = 0, deviceId = f.localId,
            simId = -1, iccid = null, mappingKey = remote.mappingKey))
        val result = SendCmdResult(uuid = f.tag, targetDeviceId = f.localId, deviceId = "executor-peer",
            success = false, state = "FAILED", message = "已过期未执行")
        f.handler.handleIncomingMessage(f.wire(result.copy(targetDeviceId = "wrong-target"), false), "executor-peer")
        assertEquals(0, f.dao.getMessageByUuid(f.tag)?.status)
        f.handler.handleIncomingMessage(f.wire(result, false), "executor-peer")
        assertEquals(-1, f.dao.getMessageByUuid(f.tag)?.status)
    }
}
