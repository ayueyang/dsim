package com.example.dsim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsListActivity : AppCompatActivity() {

    private lateinit var adapter: ConversationAdapter
    private lateinit var setupGuideContainer: View
    private lateinit var tvSetupGuideStatus: TextView
    private lateinit var tvHomeSubtitle: TextView
    private var allMessagesCache: List<SmsMessage> = emptyList()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startCloudDaemon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#123B48")
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(R.layout.activity_sms_list)
        title = "信息"
        NotificationUtils.createNotificationChannel(this)
        requestNotificationPermissionIfNeeded()
        startCloudDaemon()

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewSms)
        val fabNewConversation = findViewById<FloatingActionButton>(R.id.fabNewConversation)
        val btnOpenSettings = findViewById<TextView>(R.id.btnOpenSettings)
        val btnSetupGuideSettings = findViewById<Button>(R.id.btnSetupGuideSettings)
        setupGuideContainer = findViewById(R.id.setupGuideContainer)
        tvSetupGuideStatus = findViewById(R.id.tvSetupGuideStatus)
        tvHomeSubtitle = findViewById(R.id.tvHomeSubtitle)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ConversationAdapter(emptyList())
        recyclerView.adapter = adapter

        fabNewConversation.setOnClickListener { showCreateConversationDialog() }
        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSetupGuideSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch(Dispatchers.IO) {
            SmsSourceRepairManager.repairBorrowedMappings(this@SmsListActivity)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SmsListActivity).dsimDao()
            dao.getAllSmsMessagesFlow().collect { messages ->
                allMessagesCache = messages
                withContext(Dispatchers.Main) {
                    refreshConversationItems()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startCloudDaemon() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MqttSyncService::class.java).apply {
                action = MqttSyncService.ACTION_INIT_DAEMON
            }
        )
    }

    override fun onResume() {
        super.onResume()
        updateSetupGuide()
        refreshConversationItems()
    }

    private fun updateSetupGuide() {
        lifecycleScope.launch {
            val hasLocalSimBinding = withContext(Dispatchers.IO) {
                DsimDatabase.getDatabase(this@SmsListActivity)
                    .dsimDao()
                    .getActiveSimConfigs()
                    .any { it.bindMode != "REMOTE_SHADOW" }
            }

            val missingItems = mutableListOf<String>()
            if (!DefaultSmsManager.isDefaultSmsApp(this@SmsListActivity)) {
                missingItems += "默认短信应用"
            }
            if (!hasLocalSimBinding) {
                missingItems += "SIM 绑定"
            }
            if (!CloudSettingsManager.hasConnectionConfig(this@SmsListActivity)) {
                missingItems += "云端通道"
            }

            if (missingItems.isEmpty()) {
                setupGuideContainer.visibility = View.GONE
                return@launch
            }

            setupGuideContainer.visibility = View.VISIBLE
            tvSetupGuideStatus.text = "待配置：${missingItems.joinToString("、")}。配置完成后，这里会自动收起。"
        }
    }

    private fun refreshConversationItems() {
        val items = buildConversationItems(allMessagesCache)
        adapter.updateData(items)
        tvHomeSubtitle.text = when {
            items.isEmpty() -> "dSIM 多卡短信同步"
            items.size == 1 -> "1 个会话 · 多卡同步"
            else -> "${items.size} 个会话 · 多卡同步"
        }
    }

    private fun buildConversationItems(messages: List<SmsMessage>): List<ConversationListItem> {
        val latestByAddress = LinkedHashMap<String, SmsMessage>()
        messages.sortedBy { it.timestamp }.forEach { sms ->
            latestByAddress[sms.address] = sms
        }

        val items = latestByAddress.values
            .map { ConversationListItem.NormalConversation(it) }
            .toMutableList<ConversationListItem>()

        val otpItems = OtpConversationUtils.buildOtpItems(this, messages)
        otpItems.lastOrNull()?.let { latestOtp ->
            items += ConversationListItem.OtpConversation(latestOtp, otpItems.size)
        }

        return items.sortedByDescending { it.sortTimestamp }
    }

    private fun showCreateConversationDialog() {
        val input = EditText(this).apply {
            hint = "输入手机号"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("新建会话")
            .setView(container)
            .setPositiveButton("进入会话", null)
            .setNegativeButton("取消", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawAddress = input.text.toString().trim()
            if (rawAddress.isBlank()) {
                input.error = "请输入手机号"
                return@setOnClickListener
            }

            val normalizedAddress = GlobalNumberUtils.formatToE164(this, rawAddress)
            openChat(normalizedAddress.ifBlank { rawAddress })
            dialog.dismiss()
        }
    }

    private fun openChat(address: String, targetUuid: String? = null) {
        if (address.isBlank()) {
            Toast.makeText(this, "号码不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this@SmsListActivity, SmsChatActivity::class.java).apply {
            putExtra("CHAT_ADDRESS", address)
            if (!targetUuid.isNullOrBlank()) {
                putExtra(SmsChatActivity.EXTRA_TARGET_UUID, targetUuid)
            }
        }
        startActivity(intent)
    }

    private fun openOtpConversation() {
        startActivity(Intent(this, OtpConversationActivity::class.java))
    }

    private fun showConversationProfileDialog(address: String): Boolean {
        val profile = ConversationProfileStore.load(this, address)
        val displayNumber = PrivacyModeManager.displayPhone(this, address).ifBlank { address }

        val tipView = TextView(this).apply {
            text = "备注会显示在号码前面，号码仍然保留。头像不填时自动生成。"
            textSize = 13f
            setTextColor(Color.parseColor("#64707F"))
        }
        val remarkInput = EditText(this).apply {
            hint = "备注，例如：建设银行"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(profile.remark)
            setSelection(text.length)
        }
        val avatarInput = EditText(this).apply {
            hint = "头像文字，1-2 个字，可不填"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(profile.avatarText)
            setSelection(text.length)
        }
        val numberView = TextView(this).apply {
            text = "当前号码：$displayNumber"
            textSize = 13f
            setTextColor(Color.parseColor("#8A94A3"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(tipView)
            addView(remarkInput)
            addView(avatarInput)
            addView(numberView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("会话备注")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val remark = remarkInput.text.toString().trim()
            val avatarText = avatarInput.text.toString().trim()
            if (remark.isBlank() && avatarText.isBlank()) {
                ConversationProfileStore.clear(this, address)
            } else {
                ConversationProfileStore.save(this, address, remark, avatarText)
            }
            refreshConversationItems()
            Toast.makeText(this, "会话备注已保存", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            ConversationProfileStore.clear(this, address)
            refreshConversationItems()
            Toast.makeText(this, "已清除会话备注", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sms_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    sealed class ConversationListItem(val sortTimestamp: Long) {
        class NormalConversation(val sms: SmsMessage) : ConversationListItem(sms.timestamp)
        class OtpConversation(
            val latestOtp: OtpMessageItem,
            val count: Int
        ) : ConversationListItem(latestOtp.sms.timestamp)
    }

    inner class ConversationAdapter(private var list: List<ConversationListItem>) :
        RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

        fun updateData(newList: List<ConversationListItem>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardConversation: MaterialCardView = view.findViewById(R.id.cardConversation)
            val cardAvatar: MaterialCardView = view.findViewById(R.id.cardAvatar)
            val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
            val tvSender: TextView = view.findViewById(R.id.tvSender)
            val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
        }

        override fun getItemViewType(position: Int): Int {
            return when (list[position]) {
                is ConversationListItem.NormalConversation -> 0
                is ConversationListItem.OtpConversation -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val item = list[position]) {
                is ConversationListItem.NormalConversation -> bindNormalConversation(holder, item)
                is ConversationListItem.OtpConversation -> bindOtpConversation(holder, item)
            }
        }

        private fun bindNormalConversation(
            holder: ViewHolder,
            item: ConversationListItem.NormalConversation
        ) {
            val sms = item.sms
            val profile = ConversationProfileStore.load(holder.itemView.context, sms.address)
            holder.tvSender.text = buildConversationTitle(sms.address, profile)
            holder.tvSnippet.text = sms.body.replace('\n', ' ').trim()
            holder.tvAvatar.text = buildAvatarLabel(sms.address, profile)
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(sms.timestamp))
            applyAvatarStyle(holder, profile.remark.ifBlank { sms.address })
            holder.cardConversation.setCardBackgroundColor(Color.WHITE)
            holder.cardConversation.strokeColor = Color.parseColor("#E7ECF2")

            holder.itemView.setOnClickListener {
                openChat(sms.address)
            }
            holder.itemView.setOnLongClickListener {
                showConversationProfileDialog(sms.address)
            }
        }

        private fun bindOtpConversation(
            holder: ViewHolder,
            item: ConversationListItem.OtpConversation
        ) {
            holder.tvSender.text = "验证码"
            holder.tvSnippet.text = "最新：${item.latestOtp.senderLabel} ${item.latestOtp.code} · 共 ${item.count} 条"
            holder.tvAvatar.text = "码"
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(item.latestOtp.sms.timestamp))
            holder.cardAvatar.setCardBackgroundColor(Color.parseColor("#FFF3D8"))
            holder.tvAvatar.setTextColor(Color.parseColor("#B7791F"))
            holder.cardConversation.setCardBackgroundColor(Color.WHITE)
            holder.cardConversation.strokeColor = Color.parseColor("#F1E4C9")

            holder.itemView.setOnClickListener {
                openOtpConversation()
            }
            holder.itemView.setOnLongClickListener(null)
        }

        private fun buildConversationTitle(
            address: String,
            profile: ConversationProfile
        ): String {
            val number = PrivacyModeManager.displayPhone(this@SmsListActivity, address)
                .ifBlank { address }
            val remark = profile.remark.trim()
            return if (remark.isBlank()) number else "$remark $number"
        }

        private fun buildAvatarLabel(address: String, profile: ConversationProfile): String {
            val customAvatar = profile.avatarText.trim().take(2)
            if (customAvatar.isNotBlank()) {
                return customAvatar
            }
            val remark = profile.remark.trim()
            if (remark.isNotBlank()) {
                return remark.take(1).uppercase()
            }
            val normalized = PrivacyModeManager.normalizePhone(address)
            val digits = normalized.filter { it.isDigit() }
            return when {
                normalized == "10086" -> "移"
                normalized == "10010" -> "联"
                normalized == "10000" -> "电"
                digits.length >= 4 -> digits.takeLast(2)
                normalized.length >= 2 -> normalized.take(2).uppercase()
                else -> normalized.take(1).uppercase().ifBlank { "?" }
            }
        }

        private fun applyAvatarStyle(holder: ViewHolder, seed: String) {
            val palette = listOf(
                "#E8F4FF" to "#1677C8",
                "#EAF8F0" to "#218A52",
                "#F2EDFF" to "#6750A4",
                "#FFF0E6" to "#B45309",
                "#EAF7F7" to "#0F766E"
            )
            val index = Math.floorMod(seed.hashCode(), palette.size)
            val (background, foreground) = palette[index]
            holder.cardAvatar.setCardBackgroundColor(Color.parseColor(background))
            holder.tvAvatar.setTextColor(Color.parseColor(foreground))
        }

        override fun getItemCount() = list.size
    }
}
