package com.example.dsim

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SmsChatActivity : AppCompatActivity() {
    private var configMap: Map<String, String> = emptyMap()
    private lateinit var adapter: ChatAdapter
    private var address: String = ""
    private var selectedMappingKey: String? = null
    private var activeSimConfigs: List<SimCardConfig> = emptyList()

    private lateinit var rvChatMessages: RecyclerView
    private lateinit var btnSelectSim: Button
    private lateinit var etSmsInput: EditText
    private lateinit var btnSendSms: Button
    private lateinit var tvChatTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_chat)

        address = intent.getStringExtra("CHAT_ADDRESS") ?: "未知会话"

        tvChatTitle = findViewById(R.id.tvChatTitle)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        btnSelectSim = findViewById(R.id.btnSelectSim)
        etSmsInput = findViewById(R.id.etSmsInput)
        btnSendSms = findViewById(R.id.btnSendSms)

        tvChatTitle.text = address

        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvChatMessages.layoutManager = layoutManager

        adapter = ChatAdapter(emptyList())
        rvChatMessages.adapter = adapter

        loadMessages()
        loadSimConfigs()

        btnSelectSim.setOnClickListener {
            showSimSelector()
        }

        btnSendSms.setOnClickListener {
            sendCommand()
        }
    }

    private fun loadMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
            val configs = dao.getAllSimConfigsForUi()
            configMap = configs.associate { it.mappingKey to it.phoneNumber }

            withContext(Dispatchers.Main) {
                dao.getMessagesByAddressFlow(address).collect { messages ->
                    adapter.updateData(messages)
                    if (messages.isNotEmpty()) {
                        rvChatMessages.scrollToPosition(messages.size - 1)
                    }
                    android.util.Log.d("dSIM_UI", "聊天界面已刷新！当前消息数: ${messages.size}")
                }
            }
        }
    }

    private fun loadSimConfigs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
            activeSimConfigs = dao.getActiveSimConfigs()
        }
    }

    private fun showSimSelector() {
        if (activeSimConfigs.isEmpty()) {
            Toast.makeText(this, "没有可用的 SIM 卡配置，请先绑定", Toast.LENGTH_SHORT).show()
            return
        }

        val displayItems = activeSimConfigs.map { config ->
            "${config.phoneNumber} ${if (config.alias.isNullOrBlank()) "" else "(${config.alias})"}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择发信号码")
            .setItems(displayItems) { _, which ->
                val selected = activeSimConfigs[which]
                selectedMappingKey = selected.mappingKey
                btnSelectSim.text = selected.phoneNumber
                Toast.makeText(this, "已选择: ${selected.phoneNumber}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendCommand() {
        val body = etSmsInput.text.toString()
        if (body.isBlank()) {
            Toast.makeText(this, "⚠️ 请先输入短信内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedMappingKey == null) {
            Toast.makeText(this, "⚠️ 请先选择发信号码", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
                val password = prefs.getString("PASSWORD", "") ?: ""
                val topic = prefs.getString("TOPIC", "") ?: ""

                if (password.isBlank() || topic.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SmsChatActivity, "❌ 缺少 MQTT 配置(密码或频道)", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (MqttSyncService.globalMqttClient?.isConnected != true) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SmsChatActivity, "❌ 云端未连接，指令无法下发", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val uuid = UUID.randomUUID().toString()
                val cmdJson = JSONObject().apply {
                    put("action", "SEND_CMD")
                    put("target", address)
                    put("body", body)
                    put("mappingKey", selectedMappingKey)
                    put("uuid", uuid)
                }.toString()

                val encryptedPayload = DsimCryptoUtils.encryptMessage(cmdJson, password)
                val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8))
                message.qos = 1
                MqttSyncService.globalMqttClient?.publish(topic, message)

                val sentMsg = com.example.dsim.database.SmsMessage(
                    uuid = uuid,
                    address = address,
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    type = 2,
                    status = 0,
                    deviceId = HardwareProbeUtils.getDeviceId(this@SmsChatActivity),
                    simId = -1,
                    iccid = null,
                    mappingKey = selectedMappingKey!!
                )
                DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao().insertMessage(sentMsg)

                withContext(Dispatchers.Main) {
                    etSmsInput.text.clear()
                    Toast.makeText(this@SmsChatActivity, "✅ 跨网狙击指令已发射！", Toast.LENGTH_SHORT).show()
                }
                android.util.Log.d("dSIM_Chat", "🚀 指令已发布！目标: $address, 使用键值: $selectedMappingKey")

            } catch (e: Exception) {
                android.util.Log.e("dSIM_Chat", "发射异常崩溃", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmsChatActivity, "❌ 发射崩溃: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    inner class ChatAdapter(private var list: List<SmsMessage>) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        fun updateData(newList: List<SmsMessage>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvChatTime: TextView = view.findViewById(R.id.tvChatTime)
            val tvChatBody: TextView = view.findViewById(R.id.tvChatBody)
            val tvChatSource: TextView = view.findViewById(R.id.tvChatSource)
            val bubbleContainer: android.widget.LinearLayout = view.findViewById(R.id.bubbleContainer)
            val cardBubble: androidx.cardview.widget.CardView = view.findViewById(R.id.cardBubble)
        }

        override fun getItemViewType(position: Int): Int {
            return list[position].type
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_bubble, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sms = list[position]
            holder.tvChatBody.text = sms.body

            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvChatTime.text = sdf.format(Date(sms.timestamp))

            val remarkPhoneNumber = configMap[sms.mappingKey]
            val sourceTag = SmsTagParserUtils.parseAndFormatTag(sms.mappingKey, remarkPhoneNumber)

            val syncPrefix = if (sms.deviceId == HardwareProbeUtils.getDeviceId(holder.itemView.context)) "📱" else "☁️"
            holder.tvChatSource.text = "$syncPrefix $sourceTag"

            val isSent = sms.type == 2

            val params = holder.bubbleContainer.layoutParams as android.widget.LinearLayout.LayoutParams
            if (isSent) {
                params.gravity = android.view.Gravity.END
                holder.bubbleContainer.layoutParams = params
                holder.cardBubble.setCardBackgroundColor(android.graphics.Color.parseColor("#95EC69"))
                holder.tvChatBody.setTextColor(android.graphics.Color.BLACK)
            } else {
                params.gravity = android.view.Gravity.START
                holder.bubbleContainer.layoutParams = params
                holder.cardBubble.setCardBackgroundColor(android.graphics.Color.WHITE)
                holder.tvChatBody.setTextColor(android.graphics.Color.parseColor("#333333"))
            }
        }

        override fun getItemCount() = list.size
    }
}
