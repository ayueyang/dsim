package com.example.dsim

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtpConversationActivity : AppCompatActivity() {

    private lateinit var rvOtpMessages: RecyclerView
    private lateinit var tvOtpEmpty: TextView
    private lateinit var tvOtpSubtitle: TextView
    private lateinit var tvRuleSettings: TextView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: OtpAdapter

    private var allMessagesCache: List<SmsMessage> = emptyList()
    private var simConfigsByKeyCache: Map<String, SimCardConfig> = emptyMap()
    private var firstRender = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#123B48")
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(R.layout.activity_otp_conversation)

        rvOtpMessages = findViewById(R.id.rvOtpMessages)
        tvOtpEmpty = findViewById(R.id.tvOtpEmpty)
        tvOtpSubtitle = findViewById(R.id.tvOtpSubtitle)
        tvRuleSettings = findViewById(R.id.tvRuleSettings)

        findViewById<ImageButton>(R.id.tvBackOtp).setOnClickListener {
            DsimNavigation.backToInboxOrFinish(this)
        }
        tvRuleSettings.setOnClickListener { showRuleSettingsDialog() }

        layoutManager = LinearLayoutManager(this)
        rvOtpMessages.layoutManager = layoutManager
        adapter = OtpAdapter(emptyList())
        rvOtpMessages.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@OtpConversationActivity).dsimDao()
            dao.getAllSmsMessagesFlow().collect { messages ->
                allMessagesCache = messages
                simConfigsByKeyCache = dao.getAllSimConfigsForUi().associateBy { it.mappingKey }
                withContext(Dispatchers.Main) {
                    renderOtpMessages()
                }
            }
        }
    }

    private fun renderOtpMessages() {
        val otpItems = OtpConversationUtils.buildOtpItems(this, allMessagesCache, simConfigsByKeyCache)
        adapter.updateData(otpItems)
        tvOtpEmpty.visibility = if (otpItems.isEmpty()) View.VISIBLE else View.GONE
        tvOtpSubtitle.text = when {
            otpItems.isEmpty() -> "自动聚合验证码短信"
            otpItems.size == 1 -> "1 条验证码 · 最新在下面"
            else -> "${otpItems.size} 条验证码 · 最新在下面"
        }

        if (otpItems.isNotEmpty() && (firstRender || shouldStickToBottom())) {
            rvOtpMessages.post {
                rvOtpMessages.scrollToPosition(otpItems.lastIndex)
            }
        }
        firstRender = false
    }

    private fun shouldStickToBottom(): Boolean {
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        return lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 2
    }

    private fun showRuleSettingsDialog() {
        val settings = OtpRulesStore.loadSettings(this)
        val includeInput = EditText(this).apply {
            hint = "每行一个附加关键词"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(settings.includeKeywords.sorted().joinToString("\n"))
        }
        val excludeInput = EditText(this).apply {
            hint = "每行一个排除关键词"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(settings.excludeKeywords.sorted().joinToString("\n"))
        }
        val tipView = TextView(this).apply {
            text = "默认会识别“验证码 / 校验码 / OTP”等关键词。这里用来补充或排除特殊短信。长按卡片可以做本机临时纠错。当前临时纠错 ${OtpRulesStore.countOverrides(this@OtpConversationActivity)} 条。"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(tipView)
            addView(includeInput)
            addView(excludeInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("验证码规则")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空临时纠错", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val includeKeywords = parseLines(includeInput.text.toString())
            val excludeKeywords = parseLines(excludeInput.text.toString())
            OtpRulesStore.saveSettings(this, includeKeywords, excludeKeywords)
            renderOtpMessages()
            Toast.makeText(this, "验证码规则已保存", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            OtpRulesStore.clearAllOverrides(this)
            renderOtpMessages()
            Toast.makeText(this, "已清空临时纠错", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun parseLines(raw: String): Set<String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun copyCode(item: OtpMessageItem) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("otp_code", item.code))
        Toast.makeText(this, "已复制 ${item.code}", Toast.LENGTH_SHORT).show()
    }

    private fun openOriginalConversation(item: OtpMessageItem) {
        val intent = Intent(this, SmsChatActivity::class.java).apply {
            putExtra("CHAT_ADDRESS", item.sms.address)
            putExtra(SmsChatActivity.EXTRA_TARGET_UUID, item.sms.uuid)
        }
        startActivity(intent)
    }

    private fun showCorrectionMenu(anchor: View, item: OtpMessageItem): Boolean {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "修改发件人")
        popup.menu.add(0, 2, 1, "移出验证码")
        if (OtpRulesStore.getOverride(this, item.sms.uuid) != null) {
            popup.menu.add(0, 3, 2, "恢复自动识别")
        }
        popup.setOnMenuItemClickListener { menuItem ->
            handleCorrectionMenuClick(menuItem, item)
        }
        popup.show()
        return true
    }

    private fun handleCorrectionMenuClick(menuItem: MenuItem, item: OtpMessageItem): Boolean {
        return when (menuItem.itemId) {
            1 -> {
                showRenameSenderDialog(item)
                true
            }

            2 -> {
                val existing = OtpRulesStore.getOverride(this, item.sms.uuid)
                OtpRulesStore.saveOverride(
                    this,
                    item.sms.uuid,
                    OtpMessageOverride(forceOtp = false, senderLabel = existing?.senderLabel)
                )
                renderOtpMessages()
                Toast.makeText(this, "已暂时移出验证码会话", Toast.LENGTH_SHORT).show()
                true
            }

            3 -> {
                OtpRulesStore.clearOverride(this, item.sms.uuid)
                renderOtpMessages()
                Toast.makeText(this, "已恢复自动识别", Toast.LENGTH_SHORT).show()
                true
            }

            else -> false
        }
    }

    private fun showRenameSenderDialog(item: OtpMessageItem) {
        val input = EditText(this).apply {
            hint = "输入本机临时发件人"
            setText(item.senderLabel)
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("修改发件人")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newSender = input.text.toString().trim()
            if (newSender.isBlank()) {
                input.error = "发件人不能为空"
                return@setOnClickListener
            }

            val existing = OtpRulesStore.getOverride(this, item.sms.uuid)
            OtpRulesStore.saveOverride(
                this,
                item.sms.uuid,
                OtpMessageOverride(
                    forceOtp = existing?.forceOtp,
                    senderLabel = newSender
                )
            )
            renderOtpMessages()
            Toast.makeText(this, "已更新本机临时发件人", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    inner class OtpAdapter(private var items: List<OtpMessageItem>) :
        RecyclerView.Adapter<OtpAdapter.ViewHolder>() {

        fun updateData(newItems: List<OtpMessageItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSender: TextView = view.findViewById(R.id.tvOtpSender)
            val tvTime: TextView = view.findViewById(R.id.tvOtpTime)
            val tvSource: TextView = view.findViewById(R.id.tvOtpSource)
            val tvCode: TextView = view.findViewById(R.id.tvOtpCode)
            val tvLabel: TextView = view.findViewById(R.id.tvOtpLabel)
            val tvBody: TextView = view.findViewById(R.id.tvOtpBody)
            val btnCopy: MaterialButton = view.findViewById(R.id.btnCopyOtp)
            val tvExpand: View = view.findViewById(R.id.tvExpandOtp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_otp_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvSender.text = item.senderLabel
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(item.sms.timestamp))
            holder.tvSource.text = item.sourceLabel
            holder.tvCode.text = item.code
            holder.tvLabel.text = "${item.senderLabel} | 验证码"
            holder.tvBody.text = item.previewBody

            holder.btnCopy.setOnClickListener { copyCode(item) }
            holder.tvExpand.setOnClickListener { openOriginalConversation(item) }
            holder.itemView.setOnLongClickListener {
                showCorrectionMenu(it, item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
