package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            if (!OnboardingStateStore.isAntiFraudAcknowledged(context)) {
                return
            }
            if (!UsageModeManager.canUseCloud(context)) {
                return
            }
            val isAutoConnect = CloudSettingsManager.isAutoConnectEnabled(context)
            val config = CloudSettingsManager.getConfig(context)
            val broker = config.broker
            val topic = config.topic
            val password = config.password

            if (isAutoConnect && topic.isNotBlank() && password.isNotBlank()) {
                // MqttSyncService is a remoteMessaging FGS: allowed from BOOT_COMPLETED on API 35+
                // (dataSync is not). Keep this the only place that starts it from a boot broadcast.
                DsimLog.d("dSIM_Boot", "boot broadcast + auto-connect on -> starting MqttSyncService")
                val serviceIntent = Intent(context, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_CONNECT
                    putExtra("MQTT_BROKER", broker)
                    putExtra("MQTT_TOPIC", topic)
                    putExtra("MQTT_PASSWORD", password)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
