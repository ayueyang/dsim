package com.example.dsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttSyncService : Service() {
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var currentBroker: String = "tcp://broker.emqx.io:1883"
    private var currentTopic: String = ""
    private var currentPassword: String = ""

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dsim_sync_channel"
        
        const val ACTION_CONNECT = "com.example.dsim.CONNECT"
        const val ACTION_DISCONNECT = "com.example.dsim.DISCONNECT"
        const val ACTION_INIT_DAEMON = "com.example.dsim.INIT_DAEMON"
        
        var globalMqttClient: MqttClient? = null
        private var staticTopic: String = ""
        private var staticPassword: String = ""

        val radarEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>()
        val connectionStateFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun publishEncryptedSms(sms: SmsMessage, remarkPhone: String, topic: String, password: String) {
            try {
                if (globalMqttClient?.isConnected != true || staticTopic != topic) {
                    return
                }
                val payload = SyncPayload(sms, remarkPhone)
                val json = Gson().toJson(payload)
                val encryptedBase64 = DsimCryptoUtils.encryptMessage(json, password)
                if (encryptedBase64 != "ENCRYPTION_ERROR") {
                    val message = MqttMessage(encryptedBase64.toByteArray())
                    message.qos = 1
                    globalMqttClient?.publish(topic, message)
                    Log.d("dSIM_SyncService", "📤 已发送加密短信到云端: ${sms.address}")
                }
            } catch (e: Exception) {
                Log.e("dSIM_SyncService", "发送失败: ${e.message}")
            }
        }
        
        fun isConnected(): Boolean = globalMqttClient?.isConnected == true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "dSIM 安全同步服务", NotificationManager.IMPORTANCE_LOW).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        startForeground(NOTIFICATION_ID, createNotification("⏳ 守护进程已启动，准备连接..."))

        if (action == "ACTION_PUBLISH_MSG") {
            val payloadBase64 = intent.getStringExtra("PAYLOAD")
            val topic = intent.getStringExtra("TOPIC")
            if (!payloadBase64.isNullOrBlank() && !topic.isNullOrBlank()) {
                serviceScope.launch {
                    try {
                        val message = MqttMessage(payloadBase64.toByteArray(Charsets.UTF_8))
                        message.qos = 1
                        globalMqttClient?.publish(topic, message)
                    } catch (e: Exception) {
                        Log.e("dSIM_SyncService", "发送失败: ${e.message}")
                    }
                }
            }
            return START_STICKY
        }

        if (action == ACTION_DISCONNECT) {
            try {
                globalMqttClient?.disconnect()
                connectionStateFlow.value = false
                updateNotification("❌ 云端隧道已手动断开 (守护进程运行中)")
            } catch (e: Exception) { e.printStackTrace() }
            return START_STICKY
        }

        if (action == ACTION_INIT_DAEMON && globalMqttClient?.isConnected == true) {
            updateNotification("✅ 隧道已连接 (守护进程常驻)")
            return START_STICKY
        }

        val topic = intent?.getStringExtra("MQTT_TOPIC") ?: currentTopic
        val pwd = intent?.getStringExtra("MQTT_PASSWORD") ?: currentPassword
        val broker = intent?.getStringExtra("MQTT_BROKER") ?: currentBroker

        if (topic.isNotBlank() && pwd.isNotBlank() && broker.isNotBlank()) {
            currentTopic = topic
            currentPassword = pwd
            currentBroker = broker
            
            if (globalMqttClient?.isConnected != true || action == ACTION_CONNECT) {
                updateNotification("⏳ 正在连接云端隧道...")
                serviceScope.launch {
                    connectAndSubscribe()
                }
            }
        } else {
            updateNotification("⚠️ 守护进程已启动，等待连接配置...")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionStateFlow.value = false
        try {
            globalMqttClient?.disconnect()
            globalMqttClient?.close()
            globalMqttClient = null
        } catch (e: Exception) {}
        Log.d("dSIM_SyncService", "同步服务已关闭")
    }

    private suspend fun connectAndSubscribe() {
        try {
            val deviceId = HardwareProbeUtils.getDeviceId(this)
            val clientId = "dSIM_SEC_${deviceId}_${System.currentTimeMillis()}"
            
            if (globalMqttClient?.isConnected == true) return

            globalMqttClient = MqttClient(currentBroker, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                setAutomaticReconnect(true)
            }

            globalMqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectionLost(cause: Throwable?) {
                    updateNotification("⚠️ 云端网络已断开，正在尝试自动重连...")
                    connectionStateFlow.value = false
                }

                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    connectionStateFlow.value = true
                    if (reconnect) {
                        updateNotification("✅ 云端隧道已恢复：$currentTopic (已重新加密)")
                        try {
                            globalMqttClient?.subscribe(currentTopic, 1)
                            Log.d("dSIM_SyncService", "🔄 重连成功并完成重新订阅")
                        } catch (e: Exception) {
                            Log.e("dSIM_SyncService", "重连订阅失败", e)
                        }
                    }
                }

                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val encryptedBase64 = message?.toString() ?: return
                    Log.d("dSIM_Diagnostic", "====================================")
                    Log.d("dSIM_Diagnostic", "📥 [节点 1: 到达] 收到云端数据包，长度: ${encryptedBase64.length}")
                    
                    serviceScope.launch {
                        try {
                            val decryptedJson = DsimCryptoUtils.decryptMessage(encryptedBase64, currentPassword)
                            if (decryptedJson == null) {
                                Log.e("dSIM_Diagnostic", "❌ [节点 2: 解密失败] 密钥错误或数据损坏！当前密码长度: ${currentPassword.length}")
                                return@launch
                            }
                            Log.d("dSIM_Diagnostic", "✅ [节点 2: 解密成功] 获得 JSON，长度: ${decryptedJson.length}")

                            val jsonObject = org.json.JSONObject(decryptedJson)

                            val action = jsonObject.optString("action")
                            val senderId = jsonObject.optString("deviceId")
                            val localDeviceId = HardwareProbeUtils.getDeviceId(this@MqttSyncService)
                            val deviceName = android.os.Build.MODEL

                            if (action == "PING" && senderId != localDeviceId) {
                                try {
                                    val bm = applicationContext.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                                    val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                    val intentFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                                    val batteryStatus = applicationContext.registerReceiver(null, intentFilter)
                                    val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

                                    val isDefaultSms = DefaultSmsManager.isDefaultSmsApp(this@MqttSyncService)

                                    val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
                                    val activeSims = dao.getActiveSimConfigs()
                                    val simsJsonArray = org.json.JSONArray()
                                    for (sim in activeSims) {
                                        if (sim.bindMode == "REMOTE_SHADOW") continue

                                        val simObj = org.json.JSONObject().apply {
                                            put("mappingKey", sim.mappingKey)
                                            put("phone", sim.phoneNumber)
                                            put("mode", sim.bindMode)
                                        }
                                        simsJsonArray.put(simObj)
                                    }

                                    val pongJson = org.json.JSONObject().apply {
                                        put("action", "PONG")
                                        put("deviceId", localDeviceId)
                                        put("deviceName", deviceName)
                                        put("battery", batteryLevel)
                                        put("isCharging", isCharging)
                                        put("isDefaultSms", isDefaultSms)
                                        put("sims", simsJsonArray)
                                    }.toString()

                                    val encryptedPong = DsimCryptoUtils.encryptMessage(pongJson, currentPassword)
                                    val pongMsg = org.eclipse.paho.client.mqttv3.MqttMessage(encryptedPong.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                                    globalMqttClient?.publish(currentTopic, pongMsg)
                                    Log.d("dSIM_Telemetry", "📡 已向探测机回传完整遥测数据")
                                } catch (e: Exception) {
                                    Log.e("dSIM_Telemetry", "遥测数据抓取崩溃", e)
                                }
                                return@launch
                            }

                            if (action == "PONG" && senderId != localDeviceId) {
                                radarEventFlow.emit(decryptedJson)

                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val remoteDeviceName = jsonObject.optString("deviceName")
                                    android.widget.Toast.makeText(this@MqttSyncService, "📡 [雷达响应] 在线设备: $remoteDeviceName", android.widget.Toast.LENGTH_LONG).show()
                                }
                                Log.d("dSIM_Telemetry", "📡 收到遥测数据包")
                                return@launch
                            }

                            if (action == "SEND_CMD") {
                                val target = jsonObject.optString("target")
                                val smsBody = jsonObject.optString("body")
                                val mappingKey = jsonObject.optString("mappingKey")
                                val uuid = jsonObject.optString("uuid")

                                val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
                                val myConfig = dao.getSimConfigByKey(mappingKey)

                                if (myConfig != null && myConfig.isActive && myConfig.bindMode != "REMOTE_SHADOW") {
                                    try {
                                        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                            this@MqttSyncService.getSystemService(android.telephony.SmsManager::class.java)
                                        } else {
                                            @Suppress("DEPRECATION")
                                            android.telephony.SmsManager.getDefault()
                                        }

                                        smsManager.sendTextMessage(target, null, smsBody, null, null)
                                        Log.d("dSIM_Fire", "💥 物理电波已发射！目标: $target")

                                        val sentMsg = com.example.dsim.database.SmsMessage(
                                            uuid = uuid,
                                            address = target,
                                            body = smsBody,
                                            timestamp = System.currentTimeMillis(),
                                            type = 2,
                                            status = 1,
                                            deviceId = HardwareProbeUtils.getDeviceId(this@MqttSyncService),
                                            simId = -1,
                                            iccid = null,
                                            mappingKey = mappingKey
                                        )
                                        dao.insertMessage(sentMsg)
                                    } catch (e: Exception) {
                                        Log.e("dSIM_Fire", "💥 物理发射异常", e)
                                    }
                                } else {
                                    Log.w("dSIM_Fire", "⚠️ 收到发信指令，但指定的卡为云端影子卡或未激活，不执行本地物理发射。mappingKey=$mappingKey")
                                }
                                return@launch
                            }

                            val payload = try {
                                gson.fromJson(decryptedJson, SyncPayload::class.java)
                            } catch (e: Exception) {
                                Log.e("dSIM_Diagnostic", "❌ [节点 3: 解析失败] JSON 转 SyncPayload 崩溃: ${e.message}")
                                return@launch
                            }
                            val sms = payload.sms
                            Log.d("dSIM_Diagnostic", "✅ [节点 3: 解析成功] 拆出短信，UUID: ${sms.uuid}")

                            Log.d("dSIM_Diagnostic", "🔍 [节点 4: 来源比对] 本机 ID: $localDeviceId, 远端 ID: ${sms.deviceId}")
                            
                            if (sms.deviceId == localDeviceId) {
                                Log.w("dSIM_Diagnostic", "⚠️ [节点 4: 拦截] 发现远端 ID 和本机 ID 完全一样！已被防回音机制丢弃！")
                                return@launch
                            }
                            Log.d("dSIM_Diagnostic", "✅ [节点 4: 来源合法] 确认是外部设备发来的数据")

                            val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
                            
                            val existCount = dao.checkUuidExists(sms.uuid)
                            if (existCount > 0) {
                                Log.d("dSIM_Diagnostic", "⚠️ [节点 5: 拦截] 该 UUID 已存在，防止重复气泡！")
                                return@launch
                            }
                            
                            val existingConfig = dao.getSimConfigByKey(sms.mappingKey)
                            if (existingConfig == null) {
                                val shadowConfig = com.example.dsim.database.SimCardConfig(
                                    mappingKey = sms.mappingKey,
                                    phoneNumber = "${payload.remarkPhone} (云端)",
                                    bindMode = "REMOTE_SHADOW",
                                    isActive = true
                                )
                                dao.saveSimConfig(shadowConfig)
                                Log.d("dSIM_Diagnostic", "📝 [影子花名册] 自动注册远程卡: ${payload.remarkPhone}")
                            }

                            val safeSms = sms.copy(id = 0L)
                            dao.insertMessage(safeSms)
                            Log.d("dSIM_Diagnostic", "🎉 [节点 5: 入库成功] 远端数据已分配本地新 ID 并写入 Room 数据库！")
                            Log.d("dSIM_Diagnostic", "====================================")
                            
                            NotificationUtils.showNewMessageNotification(this@MqttSyncService, safeSms, payload.remarkPhone)

                        } catch (e: Exception) {
                            Log.e("dSIM_Diagnostic", "❌ [致命错误] 流水线未知崩溃", e)
                        }
                    }
                }
            })

            globalMqttClient?.connect(options)
            
            globalMqttClient?.subscribe(currentTopic, 1)

            staticTopic = currentTopic
            staticPassword = currentPassword

            updateNotification("✅ 云端隧道已建立：$currentTopic (AES-256加密)")
        } catch (e: Exception) {
            updateNotification("❌ 接入公共服务器失败，请检查网络后再试。")
            stopSelf()
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dSIM 分布式短信堡垒")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
