package com.example.dsim

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceManagerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)
        
        container = findViewById(R.id.deviceListContainer)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshRadar)

        btnRefresh.setOnClickListener { refreshAll() }
        
        lifecycleScope.launch {
            MqttSyncService.radarEventFlow.collect { jsonString ->
                try {
                    val json = org.json.JSONObject(jsonString)
                    withContext(Dispatchers.Main) {
                        addDeviceCard(
                            deviceName = json.optString("deviceName", "未知"),
                            deviceId = json.optString("deviceId", ""),
                            battery = json.optInt("battery", 0),
                            isCharging = json.optBoolean("isCharging", false),
                            isDefaultSms = json.optBoolean("isDefaultSms", false),
                            simsArray = json.optJSONArray("sims"),
                            isLocal = false
                        )
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        refreshAll()
    }

    private fun refreshAll() {
        container.removeAllViews()
        renderLocalDevice()
        sendRadarPing()
    }

    private fun renderLocalDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intentFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = applicationContext.registerReceiver(null, intentFilter)
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val isDefaultSms = DefaultSmsManager.isDefaultSmsApp(this@DeviceManagerActivity)
            
            val dao = com.example.dsim.database.DsimDatabase.getDatabase(this@DeviceManagerActivity).dsimDao()
            val activeSims = dao.getActiveSimConfigs()
            
            val simsArray = org.json.JSONArray()
            for (sim in activeSims) {
                simsArray.put(org.json.JSONObject().put("phone", sim.phoneNumber).put("mode", sim.bindMode).put("mappingKey", sim.mappingKey))
            }

            withContext(Dispatchers.Main) {
                addDeviceCard(
                    deviceName = android.os.Build.MODEL,
                    deviceId = HardwareProbeUtils.getDeviceId(this@DeviceManagerActivity),
                    battery = batteryLevel,
                    isCharging = isCharging,
                    isDefaultSms = isDefaultSms,
                    simsArray = simsArray,
                    isLocal = true
                )
            }
        }
    }

    private fun sendRadarPing() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
            val password = prefs.getString("PASSWORD", "") ?: ""
            val topic = prefs.getString("TOPIC", "") ?: ""
            if (password.isBlank() || MqttSyncService.globalMqttClient?.isConnected != true) return@launch
            
            val pingJson = org.json.JSONObject().apply {
                put("action", "PING")
                put("deviceId", HardwareProbeUtils.getDeviceId(this@DeviceManagerActivity))
            }.toString()
            
            try {
                val encrypted = DsimCryptoUtils.encryptMessage(pingJson, password)
                val msg = org.eclipse.paho.client.mqttv3.MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                MqttSyncService.globalMqttClient?.publish(topic, msg)
            } catch (e: Exception) {}
        }
    }

    private fun addDeviceCard(deviceName: String, deviceId: String, battery: Int, isCharging: Boolean, isDefaultSms: Boolean, simsArray: org.json.JSONArray?, isLocal: Boolean) {
        for (i in 0 until container.childCount) {
            if (container.getChildAt(i).tag == deviceId) return
        }

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            tag = deviceId
            setPadding(40, 40, 40, 40)
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            if (android.os.Build.VERSION.SDK_INT >= 21) elevation = 8f
        }

        val titleColor = if (isLocal) "#4CAF50" else "#9C27B0" 
        val localTag = if (isLocal) "(📍 本机)" else "(☁️ 远端在线)"
        val chargeTag = if (isCharging) "🔌" else "🔋"
        val smsTag = if (isDefaultSms) "✅ 正常接管" else "❌ 权限丢失"

        var simsInfo = ""
        if (simsArray != null && simsArray.length() > 0) {
            for (i in 0 until simsArray.length()) {
                val sim = simsArray.getJSONObject(i)
                val mode = sim.optString("mode")
                val modeStr = when {
                    mode == "REMOTE_SHADOW" -> "☁️ 影子映射"
                    mode.contains("ROOT") -> "✅ 极客Root"
                    else -> "⚠️ 无Root降级"
                }
                simsInfo += " • ${sim.optString("phone")} [$modeStr]<br>&nbsp;&nbsp;&nbsp;🔑 <small><font color='#888888'>${sim.optString("mappingKey")}</font></small><br>"
            }
        } else {
            simsInfo = " • (未挂载任何活跃卡片)<br>"
        }

        val contentText = """
            |<font color='$titleColor'><b>🖥️ $deviceName $localTag</b></font><br><br>
            |<b>🏷️ 设备指纹：</b>${deviceId.take(8)}...<br>
            |<b>$chargeTag 电池状态：</b>${battery}%<br>
            |<b>🛡️ 短信大权：</b>$smsTag<br><br>
            |<b>💳 挂载号卡引擎：</b><br>
            |${simsInfo.trimEnd()}
        """.trimMargin()

        val tv = TextView(this).apply {
            text = HtmlCompat.fromHtml(contentText, HtmlCompat.FROM_HTML_MODE_LEGACY)
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }

        card.addView(tv)
        if (isLocal) container.addView(card, 0) else container.addView(card)
    }
}
