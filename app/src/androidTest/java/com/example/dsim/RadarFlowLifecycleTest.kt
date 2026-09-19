package com.example.dsim

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * T1.2 acceptance: radar events are UI hints, so a backgrounded or busy collector must neither
 * suspend the inbound handler (it runs inside the InboundCommitGate critical section, W22) nor
 * keep collecting after the Activity leaves STARTED.
 */
@RunWith(AndroidJUnit4::class)
class RadarFlowLifecycleTest {

    private fun subscriptions() = MqttSyncService.radarEventFlow.subscriptionCount.value

    private fun awaitSubscriptions(expected: Int, timeoutMs: Long = 10_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var value = subscriptions()
        while (value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            value = subscriptions()
        }
        return value
    }

    private fun awaitState(scenario: ActivityScenario<*>, state: Lifecycle.State, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (scenario.state != state && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test fun backgroundedActivityStopsCollectingAndResumesOnReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(DeviceManagerActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals("STARTED activity must collect radar events", 1, awaitSubscriptions(1))

            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            assertEquals("backgrounded activity must stop collecting", 0, awaitSubscriptions(0))

            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals("returning to foreground must collect again", 1, awaitSubscriptions(1))
        }
        assertEquals("destroyed activity must not leak a collector", 0, awaitSubscriptions(0))
    }

    @Test fun onlyTheVisibleActivityOfTwoStackedOnesCollects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(DeviceManagerActivity::class.java).use { deviceManager ->
            deviceManager.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals(1, awaitSubscriptions(1))

            ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
                settings.moveToState(Lifecycle.State.RESUMED)
                instrumentation.waitForIdleSync()
                awaitState(deviceManager, Lifecycle.State.CREATED)
                assertEquals("stacked: only the STARTED activity collects", 1, awaitSubscriptions(1))

                settings.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertEquals("both backgrounded: nobody collects", 0, awaitSubscriptions(0))
            }
        }
        assertEquals(0, awaitSubscriptions(0))
    }

    @Test fun busyMainThreadDoesNotSuspendTheEmitter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals("collector must be active before the test", 1, awaitSubscriptions(1))

            // Occupy the main thread so the collector cannot run, like a janky UI would.
            val blocker = thread(name = "dsim-main-blocker") {
                instrumentation.runOnMainSync { Thread.sleep(3_000) }
            }
            Thread.sleep(400)
            val startedAt = System.nanoTime()
            var accepted = 0
            // 200 events, far beyond extraBufferCapacity = 64: oldest are dropped, none suspend.
            repeat(200) { if (MqttSyncService.radarEventFlow.tryEmit("radar-$it")) accepted++ }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            blocker.join(10_000)
            instrumentation.waitForIdleSync()

            assertEquals("every tryEmit must succeed while the collector is stalled", 200, accepted)
            assertTrue("emitter took ${elapsedMs}ms with a busy main thread", elapsedMs < 200)
            assertEquals("stalled collector must survive dropped events", 1, awaitSubscriptions(1))
        }
        assertEquals(0, awaitSubscriptions(0))
    }
}
