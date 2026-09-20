package com.example.dsim

import android.content.Intent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteSenderUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun selectorHidesLocalAndInactiveCardsAndLocalRetryIsDisabled(): Unit = runBlocking {
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val tag = UUID.randomUUID().toString()
        val local = SimCardConfig("local-$tag", "+15550000001", bindMode = "NO_ROOT", deviceId = HardwareProbeUtils.getDeviceId(context))
        val remote = SimCardConfig("remote-$tag", "+15550000002", bindMode = "REMOTE_SHADOW", deviceId = "peer-$tag")
        val inactive = remote.copy(mappingKey = "inactive-$tag", isActive = false)
        val sms = SmsMessage(uuid = tag, address = "T23-$tag", body = "local stuck message", timestamp = System.currentTimeMillis(),
            type = 2, status = -1, deviceId = local.deviceId, simId = -1, iccid = null, mappingKey = local.mappingKey)
        listOf(local, remote, inactive).forEach { dao.saveSimConfig(it) }
        dao.insertMessage(sms)
        ConversationSenderStore.savePreferredMappingKey(context, sms.address, local.mappingKey)
        try {
            ActivityScenario.launch<SmsChatActivity>(Intent(context, SmsChatActivity::class.java)
                .putExtra("CHAT_ADDRESS", sms.address)).use { scenario ->
                withTimeout(10000) {
                    var ready = false
                    while (!ready) {
                        scenario.onActivity { activity ->
                            val configs = SmsChatActivity::class.java.getDeclaredField("activeSimConfigs")
                                .apply { isAccessible = true }.get(activity) as List<*>
                            val row = activity.findViewById<RecyclerView>(R.id.rvChatMessages)
                                .findViewHolderForAdapterPosition(0)
                            if (configs.isNotEmpty() && row != null) {
                                assertTrue(configs.all { (it as SimCardConfig).bindMode == "REMOTE_SHADOW" && it.isActive })
                                assertTrue(configs.any { (it as SimCardConfig).mappingKey == remote.mappingKey })
                                val selected = SmsChatActivity::class.java.getDeclaredField("selectedMappingKey")
                                    .apply { isAccessible = true }.get(activity)
                                assertNotEquals(local.mappingKey, selected)
                                val status = row.itemView.findViewById<TextView>(R.id.tvChatStatus)
                                assertFalse(status.isEnabled)
                                assertFalse(status.isClickable)
                                assertEquals(activity.getString(R.string.sms_local_retry_unavailable), status.text.toString())
                                SmsChatActivity::class.java.getDeclaredMethod("retrySend", SmsMessage::class.java)
                                    .apply { isAccessible = true }.invoke(activity, sms)
                                ready = true
                            }
                        }
                        if (!ready) delay(50)
                    }
                }
            }
            assertEquals(-1, dao.getMessageByUuid(sms.uuid)?.status)
        } finally {
            listOf(local, remote, inactive).forEach { dao.deleteSimConfigByKey(it.mappingKey) }
            DsimDatabase.getDatabase(context).openHelper.writableDatabase.execSQL(
                "DELETE FROM sms_messages WHERE uuid = ?", arrayOf(tag))
        }
    }

    @Test fun emptyRemoteSelectionDisablesSend(): Unit = runBlocking {
        val liveMarker = InstrumentationRegistry.getArguments().getString("t23LiveMarker")
        if (liveMarker != null) {
            // Opt-in two-device UI/wire acceptance. Peer is configured to refuse carrier sending.
            SyncOutbox.requestFlush(context)
            withTimeout(90000) { while (!MqttSyncService.isConnected()) delay(200) }
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            withTimeout(90000) { while (dao.getActiveSimConfigs().none { SmsChatActivity.isSelectableSender(it) }) delay(200) }
            ActivityScenario.launch<SmsChatActivity>(Intent(context, SmsChatActivity::class.java)
                .putExtra("CHAT_ADDRESS", "10086")).use { scenario ->
                withTimeout(10000) {
                    var ready = false
                    while (!ready) {
                        scenario.onActivity { activity ->
                            val send = activity.findViewById<ImageButton>(R.id.btnSendSms)
                            if (send.isEnabled) {
                                activity.findViewById<EditText>(R.id.etSmsInput).setText(liveMarker)
                                send.performClick()
                                ready = true
                            }
                        }
                        if (!ready) delay(100)
                    }
                }
                withTimeout(90000) {
                    while (dao.getAllSmsMessages().none { it.body == liveMarker && it.type == 2 && it.status == -1 }) delay(200)
                }
                assertEquals(1, dao.getAllSmsMessages().count { it.body == liveMarker })
            }
            return@runBlocking
        }
        val local = SimCardConfig("local", "", bindMode = "NO_ROOT")
        assertFalse(SmsChatActivity.isSelectableSender(local))
        assertFalse(SmsChatActivity.isSelectableSender(null))
        assertFalse(SmsChatActivity.isSelectableSender(local.copy(bindMode = "REMOTE_SHADOW", isActive = false)))
        ActivityScenario.launch<SmsChatActivity>(Intent(context, SmsChatActivity::class.java)
            .putExtra("CHAT_ADDRESS", "T23-empty-" + UUID.randomUUID())).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<ImageButton>(R.id.btnSendSms).isEnabled)
                assertEquals("暂无可代发的远端卡", activity.getString(R.string.sms_remote_only_empty))
            }
        }
    }
}
