package com.example.dsim

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.google.android.material.card.MaterialCardView
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
    private var configMap: Map<String, SimCardConfig> = emptyMap()
    private lateinit var adapter: ChatAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var address: String = ""
    private var selectedMappingKey: String? = null
    private var activeSimConfigs: List<SimCardConfig> = emptyList()
    private var targetMessageUuid: String? = null
    private var hasScrolledToTarget = false

    private lateinit var rvChatMessages: RecyclerView
    private lateinit var btnSelectSim: Button
    private lateinit var etSmsInput: EditText
    private lateinit var btnSendSms: Button
    private lateinit var tvChatTitle: TextView

    companion object {
        const val EXTRA_TARGET_UUID = "TARGET_UUID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_chat)

        address = intent.getStringExtra("CHAT_ADDRESS") ?: "未知会话"
        targetMessageUuid = intent.getStringExtra(EXTRA_TARGET_UUID)

        tvChatTitle = findViewById(R.id.tvChatTitle)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        btnSelectSim = findViewById(R.id.btnSelectSim)
        etSmsInput = findViewById(R.id.etSmsInput)
        btnSendSms = findViewById(R.id.btnSendSms)

        tvChatTitle.text = address

        layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager

        adapter = ChatAdapter(emptyList())
        rvChatMessages.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {
            SmsSourceRepairManager.repairBorrowedMappings(this@SmsChatActivity)
        }

        loadMessages()
        loadSimConfigs()

        btnSelectSim.setOnClickListener { showSimSelector() }
        btnSendSms.setOnClickListener { sendCommand() }
    }

    private fun loadMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
            dao.getMessagesByAddressFlow(address).collect { messages ->
                configMap = dao.getAllSimConfigsForUi().associateBy { it.mappingKey }

                withContext(Dispatchers.Main) {
                    adapter.updateData(messages)
                    focusTargetMessageIfNeeded(messages)
                }
            }
        }
    }

    private fun focusTargetMessageIfNeeded(messages: List<SmsMessage>) {
        val targetUuid = targetMessageUuid
        if (!targetUuid.isNullOrBlank() && !hasScrolledToTarget) {
            val targetIndex = messages.indexOfFirst { it.uuid == targetUuid }
            if (targetIndex >= 0) {
                hasScrolledToTarget = true
                adapter.setHighlightUuid(targetUuid)
                rvChatMessages.post {
                    layoutManager.scrollToPositionWithOffset(targetIndex, 160)
                }
                rvChatMessages.postDelayed({
                    adapter.setHighlightUuid(null)
                }, 2800)
                return
            }
        }

        if (messages.isNotEmpty()) {
            rvChatMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun loadSimConfigs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
            activeSimConfigs = sortSelectableConfigs(dao.getActiveSimConfigs())
        }
    }

    private fun showSimSelector() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
            activeSimConfigs = sortSelectableConfigs(dao.getActiveSimConfigs())

            withContext(Dispatchers.Main) {
                if (activeSimConfigs.isEmpty()) {
                    Toast.makeText(this@SmsChatActivity, "没有可用号码，请先绑定", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val displayItems = activeSimConfigs.map(::buildSenderDisplay).toTypedArray()
                AlertDialog.Builder(this@SmsChatActivity)
                    .setTitle("选择发送号码")
                    .setItems(displayItems) { _, which ->
                        val selected = activeSimConfigs[which]
                        selectedMappingKey = selected.mappingKey
                        btnSelectSim.text = buildSelectedSenderLabel(selected)
                        Toast.makeText(
                            this@SmsChatActivity,
                            "已选择: ${buildSenderDisplay(selected)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun buildSenderDisplay(config: SimCardConfig): String {
        val location = if (config.bindMode == "REMOTE_SHADOW") "云端" else "本机"
        val deviceLabel = if (config.bindMode == "REMOTE_SHADOW") {
            config.alias?.trim().takeUnless { it.isNullOrBlank() } ?: buildFallbackDeviceLabel(config.deviceId)
        } else {
            ""
        }
        val slotLabel = buildSlotLabel(config)
        val phone = sanitizePhoneNumber(config.phoneNumber)
        return listOf(location, deviceLabel, slotLabel, phone)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun buildSelectedSenderLabel(config: SimCardConfig): String {
        return listOf(
            if (config.bindMode == "REMOTE_SHADOW") "云端" else "本机",
            buildSlotLabel(config),
            sanitizePhoneNumber(config.phoneNumber)
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun sortSelectableConfigs(configs: List<SimCardConfig>): List<SimCardConfig> {
        return configs.sortedWith(
            compareBy<SimCardConfig>(
                { it.bindMode == "REMOTE_SHADOW" },
                { it.alias.orEmpty() },
                { it.slotIndex ?: Int.MAX_VALUE },
                { it.phoneNumber }
            )
        )
    }

    private fun buildSlotLabel(config: SimCardConfig): String {
        return when {
            config.slotIndex != null -> "卡${config.slotIndex + 1}"
            config.subscriptionId != null -> "Sub${config.subscriptionId}"
            else -> ""
        }
    }

    private fun buildFallbackDeviceLabel(deviceId: String): String {
        if (deviceId.isBlank()) {
            return "远端设备"
        }
        return "设备${deviceId.takeLast(4)}"
    }

    private fun sanitizePhoneNumber(phoneNumber: String): String {
        return phoneNumber.removeSuffix("(云端)").trim()
    }

    private fun sendCommand() {
        val body = etSmsInput.text.toString()
        if (body.isBlank()) {
            Toast.makeText(this, "请先输入短信内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedMappingKey == null) {
            Toast.makeText(this, "请先选择发送号码", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
                val password = prefs.getString("PASSWORD", "") ?: ""
                val topic = prefs.getString("TOPIC", "") ?: ""

                if (password.isBlank() || topic.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SmsChatActivity, "缺少云端配置", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (MqttSyncService.globalMqttClient?.isConnected != true) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SmsChatActivity, "云端未连接，无法下发发送指令", Toast.LENGTH_SHORT).show()
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
                val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                }
                MqttSyncService.globalMqttClient?.publish(topic, message)

                val sentMsg = SmsMessage(
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
                    Toast.makeText(this@SmsChatActivity, "发送指令已下发", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("dSIM_Chat", "发送异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmsChatActivity, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    inner class ChatAdapter(private var list: List<SmsMessage>) :
        RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        private var highlightUuid: String? = null

        fun updateData(newList: List<SmsMessage>) {
            list = newList
            notifyDataSetChanged()
        }

        fun setHighlightUuid(uuid: String?) {
            highlightUuid = uuid
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvChatTime: TextView = view.findViewById(R.id.tvChatTime)
            val tvChatBody: TextView = view.findViewById(R.id.tvChatBody)
            val tvChatSource: TextView = view.findViewById(R.id.tvChatSource)
            val bubbleContainer: LinearLayout = view.findViewById(R.id.bubbleContainer)
            val cardBubble: MaterialCardView = view.findViewById(R.id.cardBubble)
        }

        override fun getItemViewType(position: Int): Int = list[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_bubble, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sms = list[position]
            holder.tvChatBody.text = sms.body
            holder.tvChatTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(sms.timestamp))

            val localDeviceId = HardwareProbeUtils.getDeviceId(holder.itemView.context)
            val isLocalMessage = sms.deviceId == localDeviceId
            val simConfig = configMap[sms.mappingKey]
            holder.tvChatSource.text = SmsTagParserUtils.parseAndFormatTag(
                mappingKey = sms.mappingKey,
                simConfig = simConfig,
                isLocalMessage = isLocalMessage,
                localDeviceName = DeviceNameManager.getDisplayName(holder.itemView.context)
            )

            val params = holder.bubbleContainer.layoutParams as LinearLayout.LayoutParams
            if (sms.type == 2) {
                params.gravity = Gravity.END
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#95EC69"))
                holder.tvChatBody.setTextColor(Color.BLACK)
            } else {
                params.gravity = Gravity.START
                holder.cardBubble.setCardBackgroundColor(Color.WHITE)
                holder.tvChatBody.setTextColor(Color.parseColor("#333333"))
            }
            holder.bubbleContainer.layoutParams = params

            val highlight = sms.uuid == highlightUuid
            holder.cardBubble.strokeWidth = if (highlight) (holder.itemView.resources.displayMetrics.density * 2).toInt() else 0
            holder.cardBubble.strokeColor = if (highlight) Color.parseColor("#F59E0B") else Color.TRANSPARENT
        }

        override fun getItemCount(): Int = list.size
    }
}
