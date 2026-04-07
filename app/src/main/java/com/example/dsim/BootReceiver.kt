package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
            val isAutoConnect = prefs.getBoolean("AUTO_CONNECT", false)
            val broker = prefs.getString("BROKER", "") ?: ""
            val topic = prefs.getString("TOPIC", "")
            val password = prefs.getString("PASSWORD", "")

            if (isAutoConnect && !topic.isNullOrBlank() && !password.isNullOrBlank()) {
                android.util.Log.d("dSIM_Boot", "⚡ 检测到开机广播，且自动连接已开启，正在拉起幽灵隧道...")
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
