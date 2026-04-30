package com.example.dsim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private var allMessagesCache: List<SmsMessage> = emptyList()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startCloudDaemon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_list)
        title = "信息"
        NotificationUtils.createNotificationChannel(this)
        requestNotificationPermissionIfNeeded()
        startCloudDaemon()

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewSms)
        val fabNewConversation = findViewById<FloatingActionButton>(R.id.fabNewConversation)
        val btnOpenSettings = findViewById<android.widget.Button>(R.id.btnOpenSettings)
        val btnSetupGuideSettings = findViewById<Button>(R.id.btnSetupGuideSettings)
        setupGuideContainer = findViewById(R.id.setupGuideContainer)
        tvSetupGuideStatus = findViewById(R.id.tvSetupGuideStatus)

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
            holder.tvSender.text = sms.address
            holder.tvSnippet.text = sms.body
            holder.tvAvatar.text = sms.address.take(1).uppercase()
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(sms.timestamp))

            holder.itemView.setOnClickListener {
                openChat(sms.address)
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

            holder.itemView.setOnClickListener {
                openOtpConversation()
            }
        }

        override fun getItemCount() = list.size
    }
}
