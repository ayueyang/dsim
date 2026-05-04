package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supportedActions = setOf(
            Telephony.Sms.Intents.SMS_DELIVER_ACTION,
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        )
        if (intent.action !in supportedActions) {
            return
        }

        val isDefaultSmsApp = DefaultSmsManager.isDefaultSmsApp(context)
        if (isDefaultSmsApp && intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        if (!isDefaultSmsApp && intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            return
        }

        val rawAddress = messages[0].displayOriginatingAddress ?: "Unknown"
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val timestamp = messages[0].timestampMillis
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cleanAddress = GlobalNumberUtils.formatToE164(context, rawAddress)
                val subId = extractSubscriptionId(intent)
                val slotIndex = extractSlotIndex(intent)
                val deviceId = HardwareProbeUtils.getDeviceId(context)
                val dao = DsimDatabase.getDatabase(context).dsimDao()
                val activeConfigs = dao.getActiveSimConfigs().filter { it.bindMode != "REMOTE_SHADOW" }
                val source = SmsSourceResolver.resolveIncomingLocalSource(
                    activeConfigs = activeConfigs,
                    deviceId = deviceId,
                    subscriptionId = subId,
                    slotIndex = slotIndex
                )
                val receivingPhone = source.matchedConfig?.phoneNumber?.takeIf { it.isNotBlank() }.orEmpty()
                PrivacyModeManager.rememberOwnPhone(context, receivingPhone)

                val newSms = SmsMessage(
                    uuid = java.util.UUID.randomUUID().toString(),
                    address = cleanAddress,
                    body = body,
                    timestamp = timestamp,
                    type = 1,
                    status = 1,
                    deviceId = deviceId,
                    simId = source.simId,
                    iccid = null,
                    mappingKey = source.mappingKey
                )

                dao.insertMessage(newSms)
                SystemSmsStore.insertIncomingIfNeeded(
                    context = context,
                    address = cleanAddress,
                    body = body,
                    timestamp = timestamp,
                    subscriptionId = newSms.simId.takeIf { it >= 0 }
                )
                NotificationUtils.showNewMessageNotification(context, newSms, receivingPhone)
                publishIncomingSmsToCloud(context, newSms, source.sourcePhoneNumber)

                Log.d(
                    "dSIM_Receiver",
                    "Captured incoming SMS action=${intent.action}, from=$cleanAddress, subId=$subId, slotIndex=$slotIndex, mappingKey=${source.mappingKey}"
                )
            } catch (e: Exception) {
                Log.e("dSIM_Receiver", "Failed to process incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun extractSubscriptionId(intent: Intent): Int? {
        return listOf(
            "subscription",
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscription_id",
            "sub_id"
        ).firstNotNullOfOrNull { key ->
            intent.extras?.takeIf { it.containsKey(key) }?.getInt(key, -1)?.takeIf { it != -1 }
        }
    }

    private fun extractSlotIndex(intent: Intent): Int? {
        return listOf(
            "slot",
            "slot_id",
            "slotId",
            "simSlot",
            "phone",
            "android.telephony.extra.SLOT_INDEX"
        ).firstNotNullOfOrNull { key ->
            intent.extras?.takeIf { it.containsKey(key) }?.getInt(key, -1)?.takeIf { it != -1 }
        }
    }

    private fun publishIncomingSmsToCloud(
        context: Context,
        sms: SmsMessage,
        sourcePhoneNumber: String
    ) {
        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val password = prefs.getString("PASSWORD", "") ?: ""
        val topic = prefs.getString("TOPIC", "") ?: ""
        val client = MqttSyncService.globalMqttClient

        if (client == null || !client.isConnected || password.isBlank() || topic.isBlank()) {
            return
        }

        try {
            val payload = SyncPayload(
                sms = sms,
                remarkPhone = sourcePhoneNumber,
                deviceName = DeviceNameManager.getDisplayName(context)
            )
            val encrypted = DsimCryptoUtils.encryptMessage(Gson().toJson(payload), password)
            if (encrypted == "ENCRYPTION_ERROR") {
                return
            }

            val mqttMessage = org.eclipse.paho.client.mqttv3.MqttMessage(
                encrypted.toByteArray(Charsets.UTF_8)
            ).apply {
                qos = 1
            }
            client.publish(topic, mqttMessage)
        } catch (e: Exception) {
            Log.e("dSIM_Receiver", "Failed to forward incoming SMS to cloud", e)
        }
    }
}
