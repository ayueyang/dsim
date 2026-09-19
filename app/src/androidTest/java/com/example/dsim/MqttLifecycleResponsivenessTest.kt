package com.example.dsim

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import com.example.dsim.database.DsimDatabase

/** Real Paho connection: broker completes the handshake then silently drops QoS1 PUBACKs.
 * No carrier SMS, database clearing, saved cloud configuration changes or external broker needed.
 */
@RunWith(AndroidJUnit4::class)
class MqttLifecycleResponsivenessTest {
    @Test fun blackholedBrokerDoesNotBlockLifecycleMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("do not purge a user's pending outbox in a synthetic group", runBlocking {
            DsimDatabase.getDatabase(context).dsimDao().countOutbox() == 0
        })
        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val previousMode = prefs.getString("USAGE_MODE", null)
        UsageModeManager.setMode(context, UsageMode.RECEIVE_ONLY)
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        try {
            for (action in listOf(MqttSyncService.ACTION_DISCONNECT,
                MqttSyncService.ACTION_APPLY_LOCAL_MODE, "destroy")) {
                context.stopService(Intent(context, MqttSyncService::class.java))
                // A second CONNECT after the old instance has received onDestroy starts a new one.
                instrumentation.waitForIdleSync()
                BlackholeBroker().use { broker ->
                    ContextCompat.startForegroundService(context,
                        Intent(context, MqttSyncService::class.java).apply {
                            this.action = MqttSyncService.ACTION_CONNECT
                            putExtra("MQTT_BROKER", "tcp://127.0.0.1:${broker.port}")
                            putExtra("MQTT_TOPIC", "dsim/test/lifecycle")
                            putExtra("MQTT_PASSWORD", "synthetic-lifecycle-fixture")
                        })
                    assertTrue("subscribe must complete", broker.subscribed.await(25, TimeUnit.SECONDS))
                    assertTrue("initial snapshot must finish before dropping ACKs", broker.snapshot.await(25, TimeUnit.SECONDS))
                    Thread.sleep(200)
                    val frames = AtomicInteger()
                    val maxGapMs = AtomicLong()
                    var last = 0L
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            val now = SystemClock.elapsedRealtime()
                            if (last != 0L) maxGapMs.updateAndGet { maxOf(it, now - last) }
                            last = now
                            frames.incrementAndGet()
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    instrumentation.runOnMainSync {
                        Choreographer.getInstance().postFrameCallback(callback)
                    }
                    broker.dropAcks = true
                    if (action == "destroy") context.stopService(Intent(context, MqttSyncService::class.java))
                    else ContextCompat.startForegroundService(context,
                        Intent(context, MqttSyncService::class.java).apply { this.action = action })
                    try {
                        assertTrue("OFFLINE publication must reach the blackhole", broker.blockedPublish.await(8, TimeUnit.SECONDS))
                        val before = frames.get()
                        Thread.sleep(5_500) // longer than the ANR threshold, shorter than Paho's 15 s bound
                        val delivered = frames.get() - before
                        Log.i("dSIM_T1Test", "action=$action frames=$delivered maxMainGapMs=${maxGapMs.get()}")
                        assertTrue("main thread must continue rendering during blocked publish", delivered > 5)
                        assertTrue("main frame gap must stay below 5s", maxGapMs.get() < 5_000)
                    } finally {
                        broker.close() // unblock Paho before any main-thread cleanup assertion
                        instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
                    }
                }
            }
        } finally {
            context.stopService(Intent(context, MqttSyncService::class.java))
            scenario.close()
            prefs.edit().apply {
                if (previousMode == null) remove("USAGE_MODE") else putString("USAGE_MODE", previousMode)
            }.commit()
        }
    }

    private class BlackholeBroker : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val subscribed = CountDownLatch(1)
        val blockedPublish = CountDownLatch(1)
        val snapshot = CountDownLatch(1)
        @Volatile var dropAcks = false
        @Volatile private var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "dsim-test-broker") {
            try {
                server.accept().use { peer ->
                    socket = peer
                    val input = peer.getInputStream()
                    val output = peer.getOutputStream()
                    while (!peer.isClosed) {
                        val header = input.read()
                        if (header < 0) break
                        var length = 0
                        var multiplier = 1
                        do {
                            val b = input.read()
                            if (b < 0) return@thread
                            length += (b and 127) * multiplier
                            multiplier *= 128
                        } while (b and 128 != 0)
                        val payload = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val n = input.read(payload, offset, length - offset)
                            if (n < 0) return@thread
                            offset += n
                        }
                        when (header shr 4) {
                            1 -> output.write(byteArrayOf(0x20, 2, 0, 0))
                            8 -> {
                                output.write(byteArrayOf(0x90.toByte(), 3, payload[0], payload[1], 1))
                                subscribed.countDown()
                            }
                            3 -> {
                                val qos = (header shr 1) and 3
                                val topicLength = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
                                val bodyOffset = topicLength + 2 + if (qos > 0) 2 else 0
                                val json = DsimCryptoUtils.decryptMessage(
                                    String(payload, bodyOffset, payload.size - bodyOffset, Charsets.UTF_8),
                                    "synthetic-lifecycle-fixture")
                                val message = json?.let { MqttPayloadCodec.decode(it) }
                                if (dropAcks) {
                                    if (message is Offline) blockedPublish.countDown()
                                } else {
                                    if (qos == 1) output.write(byteArrayOf(0x40, 2, payload[topicLength + 2], payload[topicLength + 3]))
                                    output.flush()
                                    if (message is Pong) snapshot.countDown()
                                }
                            }
                            12 -> if (!dropAcks) output.write(byteArrayOf(0xd0.toByte(), 0))
                            14 -> break
                        }
                        output.flush()
                    }
                }
            } catch (_: java.io.IOException) {
                // Expected when close() releases the deliberately blocked client.
            }
        }
        override fun close() {
            socket?.close()
            server.close()
            worker.join(1_000)
        }
    }
}
