package com.example.dsim

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private lateinit var btnSendSms: ImageButton
    private lateinit var tvChatTitle: TextView

    companion object {
        const val EXTRA_TARGET_UUID = "TARGET_UUID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#123B48")
        window.navigationBarColor = Color.WHITE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(R.layout.activity_sms_chat)

        address = intent.getStringExtra("CHAT_ADDRESS") ?: "未知会话"
        targetMessageUuid = intent.getStringExtra(EXTRA_TARGET_UUID)

        tvChatTitle = findViewById(R.id.tvChatTitle)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        btnSelectSim = findViewById(R.id.btnSelectSim)
        etSmsInput = findViewById(R.id.etSmsInput)
        btnSendSms = findViewById(R.id.btnSendSms)

        tvChatTitle.text = PrivacyModeManager.displayPhone(this, address).ifBlank { address }

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

    override fun onResume() {
        super.onResume()
        tvChatTitle.text = PrivacyModeManager.displayPhone(this, address).ifBlank { address }
        adapter.notifyDataSetChanged()
        val selectedConfig = activeSimConfigs.firstOrNull { it.mappingKey == selectedMappingKey }
        if (selectedConfig != null) {
            btnSelectSim.text = buildSelectedSenderLabel(selectedConfig)
        }
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
            val configs = sortSelectableConfigs(dao.getActiveSimConfigs())
            val mappingKeys = configs.map { it.mappingKey }
            val savedKey = ConversationSenderStore.getPreferredMappingKey(
                this@SmsChatActivity,
                address
            )
            val currentKey = selectedMappingKey
            val preferredKey = when {
                !currentKey.isNullOrBlank() && currentKey in mappingKeys -> currentKey
                !savedKey.isNullOrBlank() && savedKey in mappingKeys -> savedKey
                configs.size == 1 -> configs.first().mappingKey
                mappingKeys.isNotEmpty() -> {
                    val localDeviceId = HardwareProbeUtils.getDeviceId(this@SmsChatActivity)
                    dao.findPreferredLocalMappingKeyForAddress(localDeviceId, address, mappingKeys)
                        ?: dao.findMostUsedLocalMappingKey(localDeviceId, mappingKeys)
                }

                else -> null
            }

            withContext(Dispatchers.Main) {
                activeSimConfigs = configs
                selectedMappingKey = preferredKey
                configs.firstOrNull { it.mappingKey == preferredKey }?.let {
                    btnSelectSim.text = buildSelectedSenderLabel(it)
                }
            }
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

                val dialog = BottomSheetDialog(this@SmsChatActivity)
                val content = layoutInflater.inflate(R.layout.dialog_sender_selector, null)
                val rvOptions = content.findViewById<RecyclerView>(R.id.rvSenderOptions)
                val tvSubtitle = content.findViewById<TextView>(R.id.tvSenderSheetSubtitle)
                val tvCancel = content.findViewById<TextView>(R.id.tvCancelSenderSheet)

                tvSubtitle.text = buildSenderSheetSubtitle(activeSimConfigs)
                rvOptions.layoutManager = LinearLayoutManager(this@SmsChatActivity)
                rvOptions.adapter = SenderOptionAdapter(activeSimConfigs, selectedMappingKey) { selected ->
                        selectedMappingKey = selected.mappingKey
                        ConversationSenderStore.savePreferredMappingKey(
                            this@SmsChatActivity,
                            address,
                            selected.mappingKey
                        )
                        btnSelectSim.text = buildSelectedSenderLabel(selected)
                        Toast.makeText(
                            this@SmsChatActivity,
                            "已选择: ${buildSenderDisplay(selected)}",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }

                tvCancel.setOnClickListener { dialog.dismiss() }
                dialog.setContentView(content)
                dialog.setOnShowListener {
                    val bottomSheet = dialog.findViewById<View>(
                        com.google.android.material.R.id.design_bottom_sheet
                    )
                    bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
                }
                dialog.show()
            }
        }
    }

    private fun buildSenderSheetSubtitle(configs: List<SimCardConfig>): String {
        val localCount = configs.count { it.bindMode != "REMOTE_SHADOW" }
        val cloudCount = configs.count { it.bindMode == "REMOTE_SHADOW" }
        return listOfNotNull(
            localCount.takeIf { it > 0 }?.let { "本机 $it 张" },
            cloudCount.takeIf { it > 0 }?.let { "云端 $it 张" }
        ).joinToString(" · ")
    }

    private fun buildSenderDisplay(config: SimCardConfig): String {
        val location = if (config.bindMode == "REMOTE_SHADOW") "云端" else "本机"
        val deviceLabel = if (config.bindMode == "REMOTE_SHADOW") {
            config.alias?.trim().takeUnless { it.isNullOrBlank() } ?: buildFallbackDeviceLabel(config.deviceId)
        } else {
            ""
        }
        val slotLabel = buildSlotLabel(config)
        val phone = displayPhoneNumber(config.phoneNumber)
        return listOf(location, deviceLabel, slotLabel, phone)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun buildSelectedSenderLabel(config: SimCardConfig): String {
        val phoneTail = PrivacyModeManager.normalizePhone(config.phoneNumber).takeLast(4)
        return listOf(
            if (config.bindMode == "REMOTE_SHADOW") "云端" else "本机",
            buildSlotLabel(config).ifBlank { phoneTail }
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
        return PrivacyModeManager.normalizePhone(phoneNumber)
    }

    private fun displayPhoneNumber(phoneNumber: String): String {
        return PrivacyModeManager.displayPhone(this, phoneNumber)
    }

    private fun buildSenderOptionTitle(config: SimCardConfig): String {
        val slotLabel = buildSlotLabel(config)
        return if (config.bindMode == "REMOTE_SHADOW") {
            val deviceLabel = config.alias?.trim().takeUnless { it.isNullOrBlank() }
                ?: buildFallbackDeviceLabel(config.deviceId)
            listOf("云端", deviceLabel).joinToString(" · ")
        } else {
            listOf("本机", slotLabel.ifBlank { "号码" }).joinToString(" · ")
        }
    }

    private fun buildSenderOptionMeta(config: SimCardConfig): String {
        val slotLabel = buildSlotLabel(config)
        return if (config.bindMode == "REMOTE_SHADOW") {
            listOf("远程发送", slotLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        } else {
            listOf("当前设备", slotLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    }

    private fun sendCommand() {
        val body = etSmsInput.text.toString()
        if (body.isBlank()) {
            Toast.makeText(this, "请先输入短信内容", Toast.LENGTH_SHORT).show()
            return
        }
        val mappingKey = selectedMappingKey
        if (mappingKey == null) {
            Toast.makeText(this, "请先选择发送号码", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var pendingUuid: String? = null
            try {
                val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
                val password = prefs.getString("PASSWORD", "") ?: ""
                val topic = prefs.getString("TOPIC", "") ?: ""
                val dao = DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()

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
                pendingUuid = uuid
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
                    mappingKey = mappingKey
                )
                dao.insertMessage(sentMsg)
                ConversationSenderStore.savePreferredMappingKey(
                    this@SmsChatActivity,
                    address,
                    mappingKey
                )
                publishSendCommand(
                    uuid = uuid,
                    target = address,
                    body = body,
                    mappingKey = mappingKey,
                    password = password,
                    topic = topic
                )

                withContext(Dispatchers.Main) {
                    etSmsInput.text.clear()
                    Toast.makeText(this@SmsChatActivity, "发送指令已下发", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                pendingUuid?.let {
                    DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
                        .updateMessageStatus(it, -1, e.message)
                }
                android.util.Log.e("dSIM_Chat", "发送异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmsChatActivity, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun retrySend(sms: SmsMessage) {
        if (sms.uuid.isBlank()) {
            Toast.makeText(this, "这条短信缺少编号，不能重试", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@SmsChatActivity, "云端未连接，无法重试", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
                    .updateMessageStatus(sms.uuid, 0, null)
                publishSendCommand(
                    uuid = sms.uuid,
                    target = sms.address,
                    body = sms.body,
                    mappingKey = sms.mappingKey,
                    password = password,
                    topic = topic
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmsChatActivity, "正在重试发送", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao()
                    .updateMessageStatus(sms.uuid, -1, e.message)
                android.util.Log.e("dSIM_Chat", "重试发送异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SmsChatActivity, "重试失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun publishSendCommand(
        uuid: String,
        target: String,
        body: String,
        mappingKey: String,
        password: String,
        topic: String
    ) {
        val cmdJson = JSONObject().apply {
            put("action", "SEND_CMD")
            put("target", target)
            put("body", body)
            put("mappingKey", mappingKey)
            put("uuid", uuid)
            put("deviceId", HardwareProbeUtils.getDeviceId(this@SmsChatActivity))
            put("deviceName", DeviceNameManager.getDisplayName(this@SmsChatActivity))
        }.toString()

        val encryptedPayload = DsimCryptoUtils.encryptMessage(cmdJson, password)
        if (encryptedPayload == "ENCRYPTION_ERROR") {
            throw IllegalStateException("发送指令加密失败")
        }
        val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8)).apply {
            qos = 1
        }
        MqttSyncService.globalMqttClient?.publish(topic, message)
    }

    inner class SenderOptionAdapter(
        private val configs: List<SimCardConfig>,
        private val selectedKey: String?,
        private val onSelect: (SimCardConfig) -> Unit
    ) : RecyclerView.Adapter<SenderOptionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardSenderOption)
            val badge: TextView = view.findViewById(R.id.tvSenderBadge)
            val title: TextView = view.findViewById(R.id.tvSenderTitle)
            val phone: TextView = view.findViewById(R.id.tvSenderPhone)
            val meta: TextView = view.findViewById(R.id.tvSenderMeta)
            val selected: TextView = view.findViewById(R.id.tvSenderSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sender_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val config = configs[position]
            val isCloud = config.bindMode == "REMOTE_SHADOW"
            val isSelected = config.mappingKey == selectedKey
            val density = holder.itemView.resources.displayMetrics.density

            holder.badge.text = if (isCloud) "云" else "本"
            holder.badge.setBackgroundResource(
                if (isCloud) R.drawable.bg_sender_badge_cloud else R.drawable.bg_sender_badge_local
            )
            holder.title.text = buildSenderOptionTitle(config)
            holder.phone.text = displayPhoneNumber(config.phoneNumber).ifBlank { "未填写号码" }
            holder.meta.text = buildSenderOptionMeta(config)
            holder.selected.visibility = if (isSelected) View.VISIBLE else View.GONE

            holder.card.setCardBackgroundColor(
                Color.parseColor(if (isSelected) "#F4F0FF" else "#F8FAFC")
            )
            holder.card.strokeColor = Color.parseColor(if (isSelected) "#6750A4" else "#E5EAF1")
            holder.card.strokeWidth = (density * if (isSelected) 1.6f else 1f).toInt()
            holder.card.setOnClickListener { onSelect(config) }
        }

        override fun getItemCount(): Int = configs.size
    }

    inner class ChatAdapter(private var list: List<SmsMessage>) :
        RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        private var highlightUuid: String? = null

        fun updateData(newList: List<SmsMessage>) {
            list = newList
                .groupBy { it.uuid.ifBlank { "row-${it.id}" } }
                .map { (_, duplicates) ->
                    duplicates.maxWith(
                        compareBy<SmsMessage> {
                            when (it.status) {
                                1 -> 2
                                0 -> 1
                                else -> 0
                            }
                        }.thenBy { it.timestamp }
                    )
                }
                .sortedBy { it.timestamp }
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
            val tvChatStatus: TextView = view.findViewById(R.id.tvChatStatus)
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
                localDeviceName = DeviceNameManager.getDisplayName(holder.itemView.context),
                maskPhoneNumbers = PrivacyModeManager.isEnabled(holder.itemView.context)
            )

            val params = holder.bubbleContainer.layoutParams as LinearLayout.LayoutParams
            if (sms.type == 2) {
                params.gravity = Gravity.END
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#DDF6D5"))
                holder.tvChatBody.setTextColor(Color.parseColor("#1B3520"))
                holder.tvChatSource.setTextColor(Color.parseColor("#6B8F70"))
                bindSendStatus(holder, sms)
            } else {
                params.gravity = Gravity.START
                holder.cardBubble.setCardBackgroundColor(Color.WHITE)
                holder.tvChatBody.setTextColor(Color.parseColor("#2D3742"))
                holder.tvChatSource.setTextColor(Color.parseColor("#8A94A3"))
                holder.tvChatStatus.visibility = View.GONE
                holder.tvChatStatus.setOnClickListener(null)
            }
            holder.bubbleContainer.layoutParams = params

            val highlight = sms.uuid == highlightUuid
            val density = holder.itemView.resources.displayMetrics.density
            holder.cardBubble.strokeWidth = if (highlight) {
                (density * 2).toInt()
            } else if (sms.type == 2) {
                0
            } else {
                (density * 1).toInt()
            }
            holder.cardBubble.strokeColor = when {
                highlight -> Color.parseColor("#F59E0B")
                sms.type == 2 -> Color.TRANSPARENT
                else -> Color.parseColor("#E7ECF2")
            }
        }

        override fun getItemCount(): Int = list.size

        private fun bindSendStatus(holder: ViewHolder, sms: SmsMessage) {
            holder.tvChatStatus.visibility = View.VISIBLE
            holder.tvChatStatus.setOnClickListener(null)
            when (sms.status) {
                0 -> {
                    holder.tvChatStatus.text = "发送中"
                    holder.tvChatStatus.setTextColor(Color.parseColor("#6B8F70"))
                }

                1 -> {
                    holder.tvChatStatus.text = "已发送"
                    holder.tvChatStatus.setTextColor(Color.parseColor("#7A8A7F"))
                }

                else -> {
                    holder.tvChatStatus.text = "发送失败 · 点此重试"
                    holder.tvChatStatus.setTextColor(Color.parseColor("#B42318"))
                    holder.tvChatStatus.setOnClickListener { retrySend(sms) }
                }
            }
        }
    }
}
