package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val rawAddress = messages[0].displayOriginatingAddress ?: "未知号码"
        val body = messages.joinToString("") { it.displayMessageBody }
        val timestamp = messages[0].timestampMillis

        Log.d("dSIM_Receiver", "📥 截获真实物理短信: 来自 $rawAddress, 长度: ${body.length}")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cleanAddress = GlobalNumberUtils.formatToE164(context, rawAddress)

                val subId = intent.extras?.getInt("subscription", -1)
                    ?: intent.extras?.getInt("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
                    ?: -1

                val dao = DsimDatabase.getDatabase(context).dsimDao()
                val activeConfigs = dao.getActiveSimConfigs()
                val deviceId = HardwareProbeUtils.getDeviceId(context)

                var matchedMappingKey = activeConfigs.firstOrNull()?.mappingKey ?: "UNKNOWN_REAL_SIM"
                var remarkName = cleanAddress

                for (config in activeConfigs) {
                    if (config.mappingKey.contains("SUBID_$subId")) {
                        matchedMappingKey = config.mappingKey
                        remarkName = config.phoneNumber
                        break
                    }
                }

                val newSms = SmsMessage(
                    uuid = java.util.UUID.randomUUID().toString(),
                    address = cleanAddress,
                    body = body,
                    timestamp = timestamp,
                    type = 1,
                    status = 1,
                    deviceId = deviceId,
                    simId = subId,
                    iccid = null,
                    mappingKey = matchedMappingKey
                )

                dao.insertMessage(newSms)
                Log.d("dSIM_Receiver", "✅ 真实短信已入库，UI 将自动刷新")

                NotificationUtils.showNewMessageNotification(context, newSms, remarkName)

                val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
                val password = prefs.getString("PASSWORD", "") ?: ""
                val topic = prefs.getString("TOPIC", "") ?: ""

                val client = MqttSyncService.globalMqttClient
                if (client != null && client.isConnected && password.isNotBlank() && topic.isNotBlank()) {
                    val payloadObj = SyncPayload(sms = newSms, remarkPhone = remarkName)
                    val payloadJson = Gson().toJson(payloadObj)
                    val encrypted = DsimCryptoUtils.encryptMessage(payloadJson, password)

                    if (encrypted != "ENCRYPTION_ERROR") {
                        val mqttMsg = org.eclipse.paho.client.mqttv3.MqttMessage(encrypted.toByteArray(Charsets.UTF_8))
                        mqttMsg.qos = 1
                        client.publish(topic, mqttMsg)
                        Log.d("dSIM_Receiver", "☁️ 真实物理短信已截获并成功发射至 MQTT 云端！")
                    }
                }
            } catch (e: Exception) {
                Log.e("dSIM_Receiver", "短信拦截处理崩溃", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
