package com.example.dsim
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

object DsimNetworkEngine {
    private const val TAG = "dSIM_MQTT"
    private const val BROKER_HOST = "broker-cn.emqx.io"
    private const val BROKER_PORT = 1883
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _connectionStatus = MutableStateFlow("当前状态: 未连接云端")
    val connectionStatus: StateFlow<String> = _connectionStatus
    private val _incomingSmsEvent = MutableSharedFlow<Pair<String, String>>()
    val incomingSmsEvent: SharedFlow<Pair<String, String>> = _incomingSmsEvent

    private var client: Mqtt3AsyncClient? = null
    private var currentTopic = ""

    fun connectWithPairCode(pairCode: String) {
        scope.launch {
            _connectionStatus.value = "正在连接云端..."
            currentTopic = "dSIM_channel_$pairCode"
            val clientId = "dSIM_" + UUID.randomUUID().toString().substring(0, 8)
            
            client = MqttClient.builder().useMqttVersion3().identifier(clientId)
                .serverHost(BROKER_HOST).serverPort(BROKER_PORT)
                .automaticReconnectWithDefaultConfig().buildAsync()

            client?.connect()?.whenComplete { _, throwable ->
                if (throwable != null) {
                    _connectionStatus.value = "连接失败: ${throwable.message}"
                } else {
                    _connectionStatus.value = "已接入云端！暗号: $pairCode"
                    subscribeToTopic()
                }
            }
        }
    }

    private fun subscribeToTopic() {
        client?.subscribeWith()?.topicFilter(currentTopic)?.callback { publish ->
            try {
                val json = JSONObject(String(publish.payloadAsBytes))
                if (json.optString("action") == "SYNC_SMS") {
                    scope.launch { _incomingSmsEvent.emit(Pair(json.getString("sender"), json.getString("body"))) }
                }
            } catch (e: Exception) { Log.e(TAG, "解析失败", e) }
        }?.send()
    }

    fun sendSmsToCloud(sender: String, body: String) {
        if (client == null || currentTopic.isEmpty()) return
        val json = JSONObject().apply { put("action", "SYNC_SMS"); put("sender", sender); put("body", body) }.toString()
        client?.publishWith()?.topic(currentTopic)?.payload(json.toByteArray())?.qos(MqttQos.AT_LEAST_ONCE)?.send()
    }
}