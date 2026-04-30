package com.example.dsim

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dsim.database.DeviceHistoryRecord
import com.example.dsim.database.DeviceProfile
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceManagerActivity : AppCompatActivity() {

    private lateinit var btnRefresh: Button
    private lateinit var btnSelectAllOnline: Button
    private lateinit var btnQueueSelected: Button
    private lateinit var tvSelectionHint: TextView
    private lateinit var tvRadarSummary: TextView
    private lateinit var currentDeviceContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout

    private var refreshJob: Job? = null
    private var statusTickerJob: Job? = null
    private val selectedDeviceIds = linkedSetOf<String>()
    private var latestProfiles: List<DeviceProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)

        title = "设备中心"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        btnRefresh = findViewById(R.id.btnRefreshRadar)
        btnSelectAllOnline = findViewById(R.id.btnSelectAllOnline)
        btnQueueSelected = findViewById(R.id.btnQueueSelected)
        tvSelectionHint = findViewById(R.id.tvSelectionHint)
        tvRadarSummary = findViewById(R.id.tvRadarSummary)
        currentDeviceContainer = findViewById(R.id.deviceListContainer)
        historyContainer = findViewById(R.id.deviceHistoryContainer)

        btnRefresh.setOnClickListener { refreshAll() }
        btnSelectAllOnline.setOnClickListener { toggleSelectOnlineDevices() }
        btnQueueSelected.setOnClickListener { enqueueSelectedDevices() }

        lifecycleScope.launch {
            MqttSyncService.radarEventFlow.collect {
                renderDashboard("收到设备响应，列表已刷新")
            }
        }

        updateSelectionUi()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        startStatusTicker()
    }

    override fun onPause() {
        statusTickerJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        statusTickerJob?.cancel()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshAll() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            btnRefresh.isEnabled = false
            tvRadarSummary.text = "正在刷新设备信息..."

            withContext(Dispatchers.IO) {
                DeviceDirectoryManager.saveLocalSnapshot(this@DeviceManagerActivity)
            }
            renderDashboard()

            val pingSent = withContext(Dispatchers.IO) { sendRadarPing() }

            if (pingSent) {
                tvRadarSummary.text = "已发出扫描请求，正在等待其他设备回包..."
                delay(1800)
                renderDashboard()
            } else {
                renderDashboard("云端未连接，当前只显示本机和已有设备记录")
            }

            btnRefresh.isEnabled = true
        }
    }

    private fun startStatusTicker() {
        statusTickerJob?.cancel()
        statusTickerJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                renderDashboard()
            }
        }
    }

    private suspend fun renderDashboard(summaryOverride: String? = null) {
        val (profiles, history) = withContext(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@DeviceManagerActivity).dsimDao()
            dao.getAllDeviceProfiles() to dao.getRecentDeviceHistory(20)
        }
        latestProfiles = profiles

        val now = System.currentTimeMillis()
        val onlineCount = profiles.count { !it.isLocalDevice && DeviceDirectoryManager.isOnline(it, now) }
        val queueCount = profiles.count {
            it.historyQueueStatus in setOf(
                HistorySyncQueueManager.STATUS_QUEUED,
                HistorySyncQueueManager.STATUS_RUNNING,
                HistorySyncQueueManager.STATUS_PAUSED
            )
        }

        tvRadarSummary.text = summaryOverride
            ?: "已记录 ${profiles.size} 台设备，在线 $onlineCount 台，队列中 $queueCount 台，历史 ${history.size} 条"

        currentDeviceContainer.removeAllViews()
        historyContainer.removeAllViews()

        val validIds = profiles.map { it.deviceId }.toSet()
        selectedDeviceIds.retainAll(validIds)

        if (profiles.isEmpty()) {
            currentDeviceContainer.addView(
                createPlaceholderCard("还没有设备记录", "点一次刷新后，本机会先入档；云端在线设备也会同步进来。")
            )
        } else {
            profiles.forEach { profile ->
                currentDeviceContainer.addView(createDeviceCard(profile, now))
            }
        }

        if (history.isEmpty()) {
            historyContainer.addView(
                createPlaceholderCard("还没有设备历史记录", "设备首次被记录后，这里会保留最近的扫描与状态变化。")
            )
        } else {
            history.forEach { record ->
                historyContainer.addView(createHistoryCard(record))
            }
        }

        updateSelectionUi()
    }

    private fun toggleSelectOnlineDevices() {
        val now = System.currentTimeMillis()
        val eligibleIds = latestProfiles.filter { canSelectForQueue(it, now) }.map { it.deviceId }
        if (eligibleIds.isEmpty()) {
            Toast.makeText(this, "当前没有可选择的远程在线设备", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedDeviceIds.containsAll(eligibleIds)) {
            selectedDeviceIds.removeAll(eligibleIds.toSet())
        } else {
            selectedDeviceIds.addAll(eligibleIds)
        }

        lifecycleScope.launch {
            renderDashboard()
        }
    }

    private fun enqueueSelectedDevices() {
        val now = System.currentTimeMillis()
        val selectedProfiles = latestProfiles.filter { selectedDeviceIds.contains(it.deviceId) && canSelectForQueue(it, now) }
        if (selectedProfiles.isEmpty()) {
            Toast.makeText(this, "请先选择远程在线设备", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            btnQueueSelected.isEnabled = false
            try {
                val queueId = HistorySyncQueueManager.publishQueueBatchRequest(
                    context = this@DeviceManagerActivity,
                    targets = selectedProfiles
                )
                Toast.makeText(
                    this@DeviceManagerActivity,
                    "已创建排队同步任务，共 ${selectedProfiles.size} 台设备",
                    Toast.LENGTH_SHORT
                ).show()
                selectedDeviceIds.clear()
                renderDashboard("已创建排队同步任务：$queueId")
            } catch (e: Exception) {
                Toast.makeText(
                    this@DeviceManagerActivity,
                    e.message ?: "创建历史同步队列失败",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                updateSelectionUi()
            }
        }
    }

    private fun updateSelectionUi() {
        val now = System.currentTimeMillis()
        val eligibleCount = latestProfiles.count { canSelectForQueue(it, now) }
        val selectedCount = latestProfiles.count {
            selectedDeviceIds.contains(it.deviceId) && canSelectForQueue(it, now)
        }

        btnQueueSelected.isEnabled = selectedCount > 0
        btnQueueSelected.alpha = if (btnQueueSelected.isEnabled) 1f else 0.6f
        btnQueueSelected.text = if (selectedCount > 0) {
            "开始排队同步（$selectedCount 台）"
        } else {
            "开始排队同步"
        }

        btnSelectAllOnline.isEnabled = eligibleCount > 0
        btnSelectAllOnline.alpha = if (btnSelectAllOnline.isEnabled) 1f else 0.6f
        btnSelectAllOnline.text = if (eligibleCount > 0 && selectedCount == eligibleCount) {
            "取消全选"
        } else {
            "全选在线设备"
        }

        tvSelectionHint.text = when {
            eligibleCount == 0 -> "当前没有可执行远程同步的在线设备。"
            selectedCount == 0 -> "先选设备，再点“开始排队同步”。任务会按顺序逐台执行。"
            else -> "已选中 $selectedCount 台设备，点击“开始排队同步”后会按顺序依次开始。"
        }
    }

    private fun sendRadarPing(): Boolean {
        val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
        val password = prefs.getString("PASSWORD", "") ?: ""
        val topic = prefs.getString("TOPIC", "") ?: ""
        if (password.isBlank() || topic.isBlank()) {
            return false
        }
        if (MqttSyncService.globalMqttClient?.isConnected != true) {
            return false
        }

        val pingJson = org.json.JSONObject().apply {
            put("action", "PING")
            put("deviceId", HardwareProbeUtils.getDeviceId(this@DeviceManagerActivity))
        }.toString()

        return try {
            val encrypted = DsimCryptoUtils.encryptMessage(pingJson, password)
            val message = org.eclipse.paho.client.mqttv3.MqttMessage(
                encrypted.toByteArray(Charsets.UTF_8)
            ).apply {
                qos = 1
            }
            MqttSyncService.globalMqttClient?.publish(topic, message)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun createDeviceCard(profile: DeviceProfile, now: Long): View {
        val card = createCardContainer()
        val isOnline = profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile, now)
        val sourceText = if (profile.isLocalDevice) "本机设备" else "云端设备"
        val statusText = when {
            profile.isLocalDevice -> "本机"
            isOnline -> "在线"
            else -> "离线"
        }
        val statusColor = when {
            profile.isLocalDevice -> "#2457F5"
            isOnline -> "#1F8A4D"
            else -> "#8A1F2D"
        }
        val queueText = HistorySyncQueueManager.buildQueueBadgeText(profile, now)
        val queueColor = HistorySyncQueueManager.queueStatusColor(profile, now)
        val selectable = canSelectForQueue(profile, now)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = profile.deviceName
            setTextColor(Color.parseColor("#122033"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerRow.addView(titleView)
        headerRow.addView(createBadge(statusText, statusColor))
        headerRow.addView(createSpacer(8))
        headerRow.addView(createBadge(queueText, queueColor))
        card.addView(headerRow)

        if (selectable || selectedDeviceIds.contains(profile.deviceId)) {
            val checkBox = CheckBox(this).apply {
                text = "选择此设备"
                isChecked = selectedDeviceIds.contains(profile.deviceId)
                setTextColor(Color.parseColor("#0F172A"))
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2457F5"))
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedDeviceIds.add(profile.deviceId)
                    } else {
                        selectedDeviceIds.remove(profile.deviceId)
                    }
                    updateSelectionUi()
                }
            }
            card.addView(checkBox)
        } else {
            card.addView(
                createMutedText(
                    when {
                        profile.isLocalDevice -> "本机不在这里选择，请到设置页点“本机立即开始”"
                        !profile.allowsRemoteHistorySync -> "该设备已关闭“允许远程发起历史同步”"
                        !isOnline -> "设备离线，当前不能接收同步队列指令"
                        profile.historyQueueStatus == HistorySyncQueueManager.STATUS_RUNNING -> "该设备正在执行历史同步"
                        profile.historyQueueStatus == HistorySyncQueueManager.STATUS_QUEUED -> "该设备已经在历史同步队列中"
                        else -> "当前不能重新加入队列"
                    }
                )
            )
        }

        card.addView(
            createMutedText(
                "$sourceText · ${profile.simCount} 张卡 · ${if (profile.source == "LOCAL") "本机更新" else "云端回包"}"
            )
        )

        card.addView(createInfoLine("手机号", DeviceDirectoryManager.formatPhoneNumbers(profile.phoneNumbers)))
        card.addView(createInfoLine("电量", formatBattery(profile)))
        card.addView(createInfoLine("默认短信", if (profile.isDefaultSms) "已接管" else "未接管"))
        card.addView(createInfoLine("队列说明", HistorySyncQueueManager.buildQueueDetail(profile, now)))
        card.addView(
            createInfoLine(
                if (profile.isLocalDevice) "最近更新" else "最近在线",
                "${formatDateTime(profile.lastSeenAt)} · ${formatRelativeTime(profile.lastSeenAt, now)}"
            )
        )
        card.addView(createInfoLine("首次记录", formatDateTime(profile.firstSeenAt)))
        card.addView(createInfoLine("设备ID", DeviceDirectoryManager.formatDeviceId(profile.deviceId)))

        if (selectable) {
            card.setOnClickListener {
                if (selectedDeviceIds.contains(profile.deviceId)) {
                    selectedDeviceIds.remove(profile.deviceId)
                } else {
                    selectedDeviceIds.add(profile.deviceId)
                }
                lifecycleScope.launch { renderDashboard() }
            }
        }

        return card
    }

    private fun createHistoryCard(record: DeviceHistoryRecord): View {
        val card = createCardContainer()

        val titleView = TextView(this).apply {
            text = record.deviceName
            setTextColor(Color.parseColor("#122033"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        card.addView(titleView)
        card.addView(createMutedText(formatDateTime(record.seenAt)))
        card.addView(createInfoLine("记录", record.summary))
        card.addView(createInfoLine("设备ID", DeviceDirectoryManager.formatDeviceId(record.deviceId)))

        return card
    }

    private fun createPlaceholderCard(title: String, body: String): View {
        val card = createCardContainer()
        card.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#122033"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        )
        card.addView(createMutedText(body))
        return card
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#DFE7F3"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun createBadge(text: String, color: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor(color))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun createInfoLine(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label：$value"
            setTextColor(Color.parseColor("#243246"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun createMutedText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(Color.parseColor("#66758A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.16f)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun createSpacer(widthDp: Int): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
        }
    }

    private fun canSelectForQueue(profile: DeviceProfile, now: Long): Boolean {
        if (profile.isLocalDevice) {
            return false
        }
        val isOnline = DeviceDirectoryManager.isOnline(profile, now)
        if (!isOnline) {
            return false
        }
        if (!profile.allowsRemoteHistorySync) {
            return false
        }
        return profile.historyQueueStatus !in setOf(
            HistorySyncQueueManager.STATUS_RUNNING,
            HistorySyncQueueManager.STATUS_QUEUED
        )
    }

    private fun formatBattery(profile: DeviceProfile): String {
        if (profile.batteryLevel < 0) {
            return "未知"
        }
        return buildString {
            append("${profile.batteryLevel}%")
            append(if (profile.isCharging) " · 充电中" else " · 未充电")
        }
    }

    private fun formatDateTime(timestamp: Long): String {
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        return formatter.format(Date(timestamp))
    }

    private fun formatRelativeTime(timestamp: Long, now: Long): String {
        val diff = (now - timestamp).coerceAtLeast(0L)
        val seconds = diff / 1000
        return when {
            seconds < 10 -> "刚刚"
            seconds < 60 -> "${seconds}秒前"
            seconds < 3600 -> "${seconds / 60}分钟前"
            seconds < 86400 -> "${seconds / 3600}小时前"
            else -> "${seconds / 86400}天前"
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
