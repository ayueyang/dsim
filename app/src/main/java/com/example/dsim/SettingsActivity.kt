package com.example.dsim

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvCurrentDeviceName: TextView
    private lateinit var tvSystemDeviceName: TextView
    private lateinit var etDeviceName: EditText
    private lateinit var btnSaveDeviceName: Button
    private lateinit var btnResetDeviceName: Button
    private lateinit var tvDefaultSmsStatus: TextView
    private lateinit var btnRequestDefaultSmsSetting: Button
    private lateinit var btnOpenInboxSetting: Button
    private lateinit var btnManageSimSetting: Button
    private lateinit var btnViewDevicesSetting: Button
    private lateinit var switchMuteNotificationsSetting: Switch
    private lateinit var switchPrivacyModeSetting: Switch
    private lateinit var btnOpenTestTools: Button
    private lateinit var etMqttBrokerSetting: EditText
    private lateinit var etMqttTopicSetting: EditText
    private lateinit var etMqttPasswordSetting: EditText
    private lateinit var btnSaveCloudConfig: Button
    private lateinit var btnConnectCloudSetting: Button
    private lateinit var tvCloudConnectionStatus: TextView
    private lateinit var switchAutoConnectSetting: Switch
    private lateinit var switchAutoReconnectSetting: Switch
    private lateinit var switchSystemHistoryImport: Switch
    private lateinit var switchAllowRemoteHistorySync: Switch
    private lateinit var btnImportSystemHistory: Button
    private lateinit var btnOpenRemoteQueueManager: Button
    private lateinit var btnResetHistoryImport: Button
    private lateinit var btnViewHistoryQueue: Button
    private lateinit var tvSystemHistoryImportStatus: TextView

    private var statusRefreshVersion = 0
    private var currentHistoryState = HistoryImportUiState()

    private var historyQueueDialog: AlertDialog? = null
    private var tvHistoryQueueStage: TextView? = null
    private var progressHistoryQueue: ProgressBar? = null
    private var tvHistoryQueueSummary: TextView? = null
    private var tvHistoryQueueDetail: TextView? = null
    private var btnHistoryQueuePause: Button? = null
    private var btnHistoryQueueBackground: Button? = null
    private var btnHistoryQueueManage: Button? = null
    private var queueDeviceListContainer: LinearLayout? = null
    private var queueRefreshJob: Job? = null

    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshDefaultSmsUi()
        Toast.makeText(
            this,
            if (DefaultSmsManager.isDefaultSmsApp(this)) "已设为默认短信应用" else "尚未获得默认短信权限",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener {
            DsimNavigation.backToInboxOrFinish(this)
        }

        tvCurrentDeviceName = findViewById(R.id.tvCurrentDeviceName)
        tvSystemDeviceName = findViewById(R.id.tvSystemDeviceName)
        etDeviceName = findViewById(R.id.etDeviceName)
        btnSaveDeviceName = findViewById(R.id.btnSaveDeviceName)
        btnResetDeviceName = findViewById(R.id.btnResetDeviceName)
        tvDefaultSmsStatus = findViewById(R.id.tvDefaultSmsStatus)
        btnRequestDefaultSmsSetting = findViewById(R.id.btnRequestDefaultSmsSetting)
        btnOpenInboxSetting = findViewById(R.id.btnOpenInboxSetting)
        btnManageSimSetting = findViewById(R.id.btnManageSimSetting)
        btnViewDevicesSetting = findViewById(R.id.btnViewDevicesSetting)
        switchMuteNotificationsSetting = findViewById(R.id.switchMuteNotificationsSetting)
        switchPrivacyModeSetting = findViewById(R.id.switchPrivacyModeSetting)
        btnOpenTestTools = findViewById(R.id.btnOpenTestTools)
        etMqttBrokerSetting = findViewById(R.id.etMqttBrokerSetting)
        etMqttTopicSetting = findViewById(R.id.etMqttTopicSetting)
        etMqttPasswordSetting = findViewById(R.id.etMqttPasswordSetting)
        btnSaveCloudConfig = findViewById(R.id.btnSaveCloudConfig)
        btnConnectCloudSetting = findViewById(R.id.btnConnectCloudSetting)
        tvCloudConnectionStatus = findViewById(R.id.tvCloudConnectionStatus)
        switchAutoConnectSetting = findViewById(R.id.switchAutoConnectSetting)
        switchAutoReconnectSetting = findViewById(R.id.switchAutoReconnectSetting)
        switchSystemHistoryImport = findViewById(R.id.switchSystemHistoryImport)
        switchAllowRemoteHistorySync = findViewById(R.id.switchAllowRemoteHistorySync)
        btnImportSystemHistory = findViewById(R.id.btnImportSystemHistory)
        btnOpenRemoteQueueManager = findViewById(R.id.btnOpenRemoteQueueManager)
        btnResetHistoryImport = findViewById(R.id.btnResetHistoryImport)
        btnViewHistoryQueue = findViewById(R.id.btnViewHistoryQueue)
        tvSystemHistoryImportStatus = findViewById(R.id.tvSystemHistoryImportStatus)

        refreshDeviceNameUi()
        bindPrimarySetupSettings()
        bindCloudSettings()
        bindSystemHistoryImportSettings()
        observeCloudConnectionState()
        observeHistoryQueueState()
        observeDeviceRadarUpdates()

        btnSaveDeviceName.setOnClickListener {
            val customName = etDeviceName.text.toString().trim()
            if (customName.isBlank()) {
                etDeviceName.error = "请输入设备名"
                return@setOnClickListener
            }

            DeviceNameManager.saveCustomName(this, customName)
            refreshDeviceNameUi()
            broadcastDeviceProfileIfNeeded()
            Toast.makeText(this, "设备名已保存", Toast.LENGTH_SHORT).show()
        }

        btnResetDeviceName.setOnClickListener {
            DeviceNameManager.clearCustomName(this)
            refreshDeviceNameUi()
            broadcastDeviceProfileIfNeeded()
            Toast.makeText(this, "已恢复系统默认设备名", Toast.LENGTH_SHORT).show()
        }

        if (intent.getBooleanExtra(SystemHistoryImportService.EXTRA_OPEN_QUEUE, false)) {
            window.decorView.post { openHistoryQueueDialog() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SystemHistoryImportService.EXTRA_OPEN_QUEUE, false)) {
            window.decorView.post { openHistoryQueueDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDefaultSmsUi()
        refreshCloudConnectionUi()
        startQueueRefreshTicker()
    }

    override fun onPause() {
        queueRefreshJob?.cancel()
        queueRefreshJob = null
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                DsimNavigation.backToInboxOrFinish(this)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeHistoryQueueState() {
        lifecycleScope.launch {
            SystemHistoryImportService.stateFlow.collect { state ->
                currentHistoryState = state
                refreshSystemHistoryImportUi()
                updateHistoryQueueDialog(state)
            }
        }
    }

    private fun observeDeviceRadarUpdates() {
        lifecycleScope.launch {
            MqttSyncService.radarEventFlow.collect {
                if (historyQueueDialog?.isShowing == true) {
                    updateHistoryQueueDialog(currentHistoryState)
                }
            }
        }
    }

    private fun startQueueRefreshTicker() {
        if (queueRefreshJob?.isActive == true) {
            return
        }
        queueRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                if (historyQueueDialog?.isShowing == true) {
                    updateHistoryQueueDialog(currentHistoryState)
                }
            }
        }
    }

    private fun refreshDeviceNameUi() {
        val customName = DeviceNameManager.getCustomName(this)
        val currentName = DeviceNameManager.getDisplayName(this)
        val systemName = DeviceNameManager.getSystemName()

        tvCurrentDeviceName.text = "当前生效：$currentName"
        tvSystemDeviceName.text = "系统默认：$systemName"
        etDeviceName.setText(customName)
        etDeviceName.hint = systemName
    }

    private fun bindPrimarySetupSettings() {
        refreshDefaultSmsUi()

        btnRequestDefaultSmsSetting.setOnClickListener {
            val intent = DefaultSmsManager.createRequestRoleIntent(this)
            if (intent != null) {
                defaultSmsLauncher.launch(intent)
            } else {
                refreshDefaultSmsUi()
                Toast.makeText(this, "已经是默认短信应用", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenInboxSetting.setOnClickListener {
            startActivity(
                Intent(this, SmsListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }

        btnManageSimSetting.setOnClickListener {
            startActivity(Intent(this, SimBindingActivity::class.java))
        }

        btnViewDevicesSetting.setOnClickListener {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
        switchMuteNotificationsSetting.isChecked = prefs.getBoolean("IS_MUTED", false)
        switchMuteNotificationsSetting.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("IS_MUTED", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "新消息通知已静音" else "新消息通知已恢复响铃",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchPrivacyModeSetting.isChecked = PrivacyModeManager.isEnabled(this)
        switchPrivacyModeSetting.setOnCheckedChangeListener { _, isChecked ->
            PrivacyModeManager.setEnabled(this, isChecked)
            refreshPrivacySensitiveNotifications()
            Toast.makeText(
                this,
                if (isChecked) "隐私模式已开启，手机号将打码显示" else "隐私模式已关闭，手机号将完整显示",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnOpenTestTools.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun refreshPrivacySensitiveNotifications() {
        lifecycleScope.launch {
            HistoryQueueNotificationHelper.refresh(this@SettingsActivity)
        }
        if (MqttSyncService.globalMqttClient != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_REFRESH_NOTIFICATION
                }
            )
        }
    }

    private fun refreshDefaultSmsUi() {
        val isDefault = DefaultSmsManager.isDefaultSmsApp(this)
        tvDefaultSmsStatus.text = if (isDefault) {
            "默认短信应用：已完成。系统短信收发、通知和会话入口都可以由 dSIM 接管。"
        } else {
            "默认短信应用：未完成。建议先设置为默认短信应用，避免收发和系统库行为不一致。"
        }
        btnRequestDefaultSmsSetting.isEnabled = !isDefault
        btnRequestDefaultSmsSetting.alpha = if (isDefault) 0.6f else 1f
        btnRequestDefaultSmsSetting.text = if (isDefault) "已是默认短信应用" else "设为默认短信应用"
    }

    private fun bindCloudSettings() {
        val config = CloudSettingsManager.getConfig(this)
        etMqttBrokerSetting.setText(config.broker)
        etMqttTopicSetting.setText(config.topic)
        etMqttPasswordSetting.setText(config.password)

        btnSaveCloudConfig.setOnClickListener {
            if (saveCloudConfigFromInputs()) {
                if (CloudSettingsManager.isAutoConnectEnabled(this)) {
                    startCloudDaemon()
                }
                Toast.makeText(this, "云端配置已保存", Toast.LENGTH_SHORT).show()
                refreshCloudConnectionUi()
            }
        }

        btnConnectCloudSetting.setOnClickListener {
            if (MqttSyncService.isConnected()) {
                showManualDisconnectDialog()
            } else if (saveCloudConfigFromInputs()) {
                val savedConfig = CloudSettingsManager.getConfig(this)
                val serviceIntent = Intent(this, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_CONNECT
                    putExtra("MQTT_BROKER", savedConfig.broker)
                    putExtra("MQTT_TOPIC", savedConfig.topic)
                    putExtra("MQTT_PASSWORD", savedConfig.password)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, "正在连接云端", Toast.LENGTH_SHORT).show()
                refreshCloudConnectionUi()
            }
        }

        switchAutoConnectSetting.isChecked = CloudSettingsManager.isAutoConnectEnabled(this)
        switchAutoReconnectSetting.isChecked = CloudSettingsManager.isAutoReconnectEnabled(this)
        refreshCloudConnectionUi()

        switchAutoConnectSetting.setOnCheckedChangeListener { _, isChecked ->
            CloudSettingsManager.setAutoConnectEnabled(this, isChecked)
            if (isChecked && CloudSettingsManager.hasConnectionConfig(this)) {
                startCloudDaemon()
            }
            Toast.makeText(
                this,
                if (isChecked) "已开启开机自动连接" else "已关闭开机自动连接",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchAutoReconnectSetting.setOnCheckedChangeListener { _, isChecked ->
            CloudSettingsManager.setAutoReconnectEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "已开启断线自动重连" else "已关闭断线自动重连",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startCloudDaemon() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MqttSyncService::class.java).apply {
                action = MqttSyncService.ACTION_INIT_DAEMON
            }
        )
    }

    private fun observeCloudConnectionState() {
        lifecycleScope.launch {
            MqttSyncService.connectionStateFlow.collect {
                refreshCloudConnectionUi()
            }
        }
    }

    private fun saveCloudConfigFromInputs(): Boolean {
        val broker = etMqttBrokerSetting.text.toString().trim()
            .ifBlank { CloudSettingsManager.DEFAULT_BROKER }
        val topic = etMqttTopicSetting.text.toString().trim()
        val password = etMqttPasswordSetting.text.toString().trim()

        if (topic.isBlank()) {
            etMqttTopicSetting.error = "请输入同步主题"
            return false
        }
        if (password.isBlank()) {
            etMqttPasswordSetting.error = "请输入加密口令"
            return false
        }

        CloudSettingsManager.saveConfig(this, broker, topic, password)
        return true
    }

    private fun refreshCloudConnectionUi() {
        val isConnected = MqttSyncService.isConnected()
        val hasConfig = CloudSettingsManager.hasConnectionConfig(this)
        val config = CloudSettingsManager.getConfig(this)

        tvCloudConnectionStatus.text = when {
            isConnected -> "云端状态：已连接 ${config.topic}"
            hasConfig -> "云端状态：已保存配置，当前未连接"
            else -> "云端状态：未配置主题和口令"
        }

        btnConnectCloudSetting.text = if (isConnected) "断开云端" else "连接云端"
        btnConnectCloudSetting.isEnabled = true
        btnConnectCloudSetting.alpha = 1f
        btnConnectCloudSetting.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isConnected) "#8A1F2D" else "#2457F5")
        )

        btnSaveCloudConfig.isEnabled = !isConnected
        btnSaveCloudConfig.alpha = if (btnSaveCloudConfig.isEnabled) 1f else 0.6f
        etMqttBrokerSetting.isEnabled = !isConnected
        etMqttTopicSetting.isEnabled = !isConnected
        etMqttPasswordSetting.isEnabled = !isConnected
    }

    private fun showManualDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("手动断开云端？")
            .setMessage("手动断开后，本次运行期间不会自动重连。再次点击连接，或者重启软件、手机后，会按自动连接设置重新接入。")
            .setPositiveButton("确认断开") { _, _ ->
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MqttSyncService::class.java).apply {
                        action = MqttSyncService.ACTION_DISCONNECT
                    }
                )
                Toast.makeText(this, "已手动断开，本次不会自动重连", Toast.LENGTH_LONG).show()
                refreshCloudConnectionUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindSystemHistoryImportSettings() {
        switchSystemHistoryImport.isChecked = SystemSmsHistoryImporter.isEnabled(this)
        switchAllowRemoteHistorySync.isChecked = HistorySyncQueueManager.isRemoteStartAllowed(this)
        refreshSystemHistoryImportUi()

        switchSystemHistoryImport.setOnCheckedChangeListener { _, isChecked ->
            SystemSmsHistoryImporter.setEnabled(this, isChecked)
            refreshSystemHistoryImportUi()
            Toast.makeText(
                this,
                if (isChecked) "已开启手动导入系统历史短信" else "已关闭手动导入系统历史短信",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchAllowRemoteHistorySync.setOnCheckedChangeListener { _, isChecked ->
            HistorySyncQueueManager.setRemoteStartAllowed(this, isChecked)
            broadcastDeviceProfileIfNeeded()
            refreshSystemHistoryImportUi()
            Toast.makeText(
                this,
                if (isChecked) "已允许远程发起历史同步" else "已关闭远程发起历史同步",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnImportSystemHistory.setOnClickListener {
            if (!SystemSmsHistoryImporter.isEnabled(this)) {
                Toast.makeText(this, "请先开启“系统历史短信导入”", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                HistorySyncQueueManager.enqueueLocalOnly(this@SettingsActivity)
                openHistoryQueueDialog()
            }
        }

        btnOpenRemoteQueueManager.setOnClickListener {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        btnViewHistoryQueue.setOnClickListener {
            openHistoryQueueDialog()
        }

        btnResetHistoryImport.setOnClickListener {
            if (currentHistoryState.isRunning) {
                Toast.makeText(this, "后台任务运行中，暂时不能重置读取进度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("重置本机读取进度")
                .setMessage(
                    "这只会重置“系统历史短信”的读取断点，不会删除系统短信，也不会删除软件库现有短信。\n\n重置后，再点“本机立即开始”会从较新的系统短信重新检查。"
                )
                .setPositiveButton("确认重置") { _, _ ->
                    SystemSmsHistoryImporter.resetImportProgress(this, clearLastImportAt = true)
                    SystemHistoryImportService.clearSnapshot()
                    currentHistoryState = HistoryImportUiState()
                    refreshSystemHistoryImportUi("已重置本机读取进度，可重新开始导入。")
                    Toast.makeText(this, "已重置本机读取进度", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshSystemHistoryImportUi(resultMessage: String? = null) {
        val enabled = SystemSmsHistoryImporter.isEnabled(this)
        val localQueue = HistorySyncQueueManager.getLocalQueueSnapshot(this)
        val isRunning = currentHistoryState.isRunning
        val hasSnapshot = currentHistoryState.hasSnapshot() || localQueue.isActive()
        val isQueued = localQueue.status == HistorySyncQueueManager.STATUS_QUEUED

        btnImportSystemHistory.isEnabled = enabled && !isRunning && !isQueued
        btnImportSystemHistory.alpha = if (btnImportSystemHistory.isEnabled) 1f else 0.6f
        btnImportSystemHistory.text = when {
            isRunning -> "本机同步中..."
            isQueued && (localQueue.position ?: 1) > 1 -> "本机排队中"
            isQueued -> "本机等待开始"
            currentHistoryState.isPaused -> "继续本机导入"
            else -> "本机立即开始"
        }

        btnResetHistoryImport.isEnabled = !isRunning
        btnResetHistoryImport.alpha = if (btnResetHistoryImport.isEnabled) 1f else 0.6f
        btnViewHistoryQueue.isEnabled = hasSnapshot
        btnViewHistoryQueue.alpha = if (hasSnapshot) 1f else 0.6f
        switchSystemHistoryImport.isEnabled = !isRunning

        if (isRunning || hasSnapshot) {
            tvSystemHistoryImportStatus.text = buildInlineHistoryStatus(resultMessage, localQueue)
            return
        }

        val refreshVersion = ++statusRefreshVersion
        lifecycleScope.launch {
            val status = StringBuilder()
            if (!resultMessage.isNullOrBlank()) {
                status.append(resultMessage).append("\n\n")
            }
            status.append(SystemSmsHistoryImporter.buildStatusText(this@SettingsActivity))

            if (refreshVersion == statusRefreshVersion) {
                tvSystemHistoryImportStatus.text = status.toString()
            }
        }
    }

    private fun buildInlineHistoryStatus(
        resultMessage: String?,
        localQueue: HistorySyncQueueManager.LocalQueueSnapshot
    ): String {
        val lines = mutableListOf<String>()
        if (!resultMessage.isNullOrBlank()) {
            lines += resultMessage
        }

        lines += when (localQueue.status) {
            HistorySyncQueueManager.STATUS_QUEUED ->
                if ((localQueue.position ?: 1) > 1) "本机队列：排队第 ${localQueue.position}" else "本机队列：等待开始"
            HistorySyncQueueManager.STATUS_RUNNING -> "本机队列：同步中"
            HistorySyncQueueManager.STATUS_PAUSED -> "本机队列：已暂停"
            HistorySyncQueueManager.STATUS_COMPLETED -> "本机队列：已完成"
            HistorySyncQueueManager.STATUS_FAILED -> "本机队列：异常暂停"
            else -> "本机队列：空闲"
        }

        lines += if (localQueue.totalDevices > 1) {
            "任务类型：多设备共享队列"
        } else {
            "任务类型：本机单机任务"
        }

        if (currentHistoryState.systemCount > 0) {
            lines += "当前进度：${currentHistoryState.scannedCount}/${currentHistoryState.systemCount}"
        } else if (localQueue.progressTotal > 0) {
            lines += "当前进度：${localQueue.progressCurrent}/${localQueue.progressTotal}"
        }

        lines += "新增 ${currentHistoryState.importedCount} 条，云端确认 ${currentHistoryState.syncedCount} 条，跳过 ${currentHistoryState.skippedCount} 条。"

        if (currentHistoryState.currentAddress.isNotBlank()) {
            lines += "当前处理：${currentHistoryState.currentAddress}"
        }

        when {
            currentHistoryState.detailMessage.isNotBlank() -> lines += currentHistoryState.detailMessage
            localQueue.detail.isNotBlank() -> lines += localQueue.detail
        }

        return lines.joinToString("\n")
    }

    private fun openHistoryQueueDialog() {
        if (historyQueueDialog?.isShowing == true) {
            updateHistoryQueueDialog(currentHistoryState)
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_history_import_queue, null)
        tvHistoryQueueStage = dialogView.findViewById(R.id.tvHistoryQueueStage)
        progressHistoryQueue = dialogView.findViewById(R.id.progressHistoryQueue)
        tvHistoryQueueSummary = dialogView.findViewById(R.id.tvHistoryQueueSummary)
        tvHistoryQueueDetail = dialogView.findViewById(R.id.tvHistoryQueueDetail)
        btnHistoryQueuePause = dialogView.findViewById(R.id.btnHistoryQueuePause)
        btnHistoryQueueBackground = dialogView.findViewById(R.id.btnHistoryQueueBackground)
        btnHistoryQueueManage = dialogView.findViewById(R.id.btnHistoryQueueManage)
        queueDeviceListContainer = dialogView.findViewById(R.id.queueDeviceListContainer)

        btnHistoryQueuePause?.setOnClickListener {
            if (currentHistoryState.isRunning) {
                SystemHistoryImportService.pauseImport(this)
                Toast.makeText(this, "已请求暂停，当前这条处理完后会停下", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "当前没有正在运行的本机历史同步", Toast.LENGTH_SHORT).show()
            }
        }
        btnHistoryQueueBackground?.setOnClickListener {
            historyQueueDialog?.dismiss()
            Toast.makeText(this, "历史同步会继续在后台执行，可在通知栏查看进度", Toast.LENGTH_SHORT).show()
        }
        btnHistoryQueueManage?.setOnClickListener {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        historyQueueDialog = AlertDialog.Builder(this)
            .setTitle("历史同步任务")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .setOnDismissListener {
                historyQueueDialog = null
                tvHistoryQueueStage = null
                progressHistoryQueue = null
                tvHistoryQueueSummary = null
                tvHistoryQueueDetail = null
                btnHistoryQueuePause = null
                btnHistoryQueueBackground = null
                btnHistoryQueueManage = null
                queueDeviceListContainer = null
            }
            .show()

        updateHistoryQueueDialog(currentHistoryState)
    }

    private fun updateHistoryQueueDialog(state: HistoryImportUiState) {
        val localQueue = HistorySyncQueueManager.getLocalQueueSnapshot(this)
        tvHistoryQueueStage?.text = when (localQueue.status) {
            HistorySyncQueueManager.STATUS_QUEUED ->
                if ((localQueue.position ?: 1) > 1) "本机排队中" else "本机等待开始"
            HistorySyncQueueManager.STATUS_RUNNING -> "本机同步中"
            HistorySyncQueueManager.STATUS_PAUSED -> "本机已暂停"
            HistorySyncQueueManager.STATUS_COMPLETED -> "本机已完成"
            HistorySyncQueueManager.STATUS_FAILED -> "本机异常暂停"
            else -> state.stage.ifBlank { "当前未运行" }
        }

        progressHistoryQueue?.apply {
            val total = when {
                state.systemCount > 0 -> state.systemCount
                localQueue.progressTotal > 0 -> localQueue.progressTotal
                else -> 0
            }
            val current = when {
                state.systemCount > 0 -> state.scannedCount
                else -> localQueue.progressCurrent
            }
            isIndeterminate = total <= 0 && (state.isRunning || localQueue.status == HistorySyncQueueManager.STATUS_QUEUED)
            if (total > 0) {
                max = total
                progress = current.coerceAtMost(total)
            } else {
                progress = 0
            }
        }

        tvHistoryQueueSummary?.text = buildQueueSummary(state, localQueue)
        tvHistoryQueueDetail?.text = buildQueueDetail(state, localQueue)

        btnHistoryQueuePause?.apply {
            text = when {
                state.isRunning -> "暂停队列"
                localQueue.status == HistorySyncQueueManager.STATUS_QUEUED -> "排队中"
                localQueue.status == HistorySyncQueueManager.STATUS_PAUSED -> "已暂停"
                else -> "未运行"
            }
            isEnabled = state.isRunning
            alpha = if (isEnabled) 1f else 0.6f
        }

        btnHistoryQueueBackground?.text = if (state.isRunning) "转到后台" else "收起"
        btnHistoryQueueManage?.text = if (localQueue.totalDevices > 1) {
            "去设备中心管理队列"
        } else {
            "去设备中心发起其他设备"
        }
        refreshQueueDeviceCards(localQueue)
    }

    private fun buildQueueSummary(
        state: HistoryImportUiState,
        localQueue: HistorySyncQueueManager.LocalQueueSnapshot
    ): String {
        val lines = mutableListOf<String>()
        val total = when {
            state.systemCount > 0 -> state.systemCount
            localQueue.progressTotal > 0 -> localQueue.progressTotal
            else -> 0
        }
        val current = when {
            state.systemCount > 0 -> state.scannedCount
            else -> localQueue.progressCurrent
        }

        lines += if (total > 0) {
            "扫描进度：$current/$total"
        } else {
            "扫描进度：等待读取系统短信"
        }

        lines += if (localQueue.totalDevices > 1) {
            "任务类型：多设备共享队列"
        } else {
            "任务类型：本机单机任务"
        }

        lines += when (localQueue.status) {
            HistorySyncQueueManager.STATUS_QUEUED ->
                if ((localQueue.position ?: 1) > 1) "本机状态：排队第 ${localQueue.position}" else "本机状态：等待开始"
            HistorySyncQueueManager.STATUS_RUNNING -> "本机状态：同步中"
            HistorySyncQueueManager.STATUS_PAUSED -> "本机状态：已暂停"
            HistorySyncQueueManager.STATUS_COMPLETED -> "本机状态：已完成"
            HistorySyncQueueManager.STATUS_FAILED -> "本机状态：异常暂停"
            else -> "本机状态：空闲"
        }
        lines += "新增 ${state.importedCount} 条"
        lines += "云端确认 ${state.syncedCount} 条"
        lines += "跳过 ${state.skippedCount} 条"
        lines += "软件库约 ${state.appCount} 条"
        return lines.joinToString("\n")
    }

    private fun buildQueueDetail(
        state: HistoryImportUiState,
        localQueue: HistorySyncQueueManager.LocalQueueSnapshot
    ): String {
        val lines = mutableListOf<String>()
        if (state.currentAddress.isNotBlank()) {
            lines += "当前处理：${state.currentAddress}"
        }
        when {
            state.detailMessage.isNotBlank() -> lines += state.detailMessage
            localQueue.detail.isNotBlank() -> lines += localQueue.detail
            else -> lines += "还没有开始新的历史同步任务。"
        }
        return lines.joinToString("\n\n")
    }

    private fun refreshQueueDeviceCards(localQueue: HistorySyncQueueManager.LocalQueueSnapshot) {
        val container = queueDeviceListContainer ?: return
        lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                DsimDatabase.getDatabase(this@SettingsActivity).dsimDao().getAllDeviceProfiles()
            }
            val now = System.currentTimeMillis()
            val displayProfiles = if (localQueue.totalDevices > 1 && localQueue.queueId.isNotBlank()) {
                profiles.filter { it.historyQueueId == localQueue.queueId }
            } else {
                profiles
            }

            val sortedProfiles = displayProfiles.sortedWith(
                compareBy<DeviceProfile> {
                    when (it.historyQueueStatus) {
                        HistorySyncQueueManager.STATUS_RUNNING -> 0
                        HistorySyncQueueManager.STATUS_QUEUED -> 1
                        HistorySyncQueueManager.STATUS_PAUSED -> 2
                        HistorySyncQueueManager.STATUS_COMPLETED -> 3
                        HistorySyncQueueManager.STATUS_FAILED -> 4
                        else -> 5
                    }
                }.thenBy { it.historyQueuePosition ?: Int.MAX_VALUE }
                    .thenByDescending { it.isLocalDevice }
                    .thenByDescending { it.lastSeenAt }
            )

            container.removeAllViews()
            if (sortedProfiles.isEmpty()) {
                container.addView(createQueuePlaceholder())
                return@launch
            }

            sortedProfiles.forEach { profile ->
                container.addView(createQueueDeviceCard(profile, now))
            }
        }
    }

    private fun createQueuePlaceholder(): LinearLayout {
        return createCardContainer().apply {
            addView(
                TextView(this@SettingsActivity).apply {
                    text = "还没有设备记录"
                    setTextColor(Color.parseColor("#0F172A"))
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
            )
            addView(createMutedText("先去设备中心刷新一次，本机和在线设备都会出现在这里。"))
        }
    }

    private fun createQueueDeviceCard(profile: DeviceProfile, now: Long): LinearLayout {
        val isOnline = profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile, now)
        val onlineText = when {
            profile.isLocalDevice -> "本机"
            isOnline -> "在线"
            else -> "离线"
        }
        val onlineColor = when {
            profile.isLocalDevice -> "#2457F5"
            isOnline -> "#1F8A4D"
            else -> "#8A1F2D"
        }
        val queueText = HistorySyncQueueManager.buildQueueBadgeText(profile, now)
        val queueColor = HistorySyncQueueManager.queueStatusColor(profile, now)

        return createCardContainer().apply {
            val titleRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(this@SettingsActivity).apply {
                text = profile.deviceName
                setTextColor(Color.parseColor("#122033"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            titleRow.addView(titleView)
            titleRow.addView(createBadge(onlineText, onlineColor))
            titleRow.addView(createSpacer(8))
            titleRow.addView(createBadge(queueText, queueColor))
            addView(titleRow)

            addView(createMutedText("号码：${DeviceDirectoryManager.formatPhoneNumbers(this@SettingsActivity, profile.phoneNumbers)}"))
            addView(createInfoLine("队列说明", HistorySyncQueueManager.buildQueueDetail(profile, now)))
            addView(
                createInfoLine(
                    "最近在线",
                    "${formatDateTime(profile.lastSeenAt)} · ${formatRelativeTime(profile.lastSeenAt, now)}"
                )
            )
        }
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun createBadge(text: String, color: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor(color))
            }
        }
    }

    private fun createInfoLine(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label：$value"
            setTextColor(Color.parseColor("#243246"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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

    private fun formatDateTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
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

    private fun broadcastDeviceProfileIfNeeded() {
        if (!MqttSyncService.isConnected()) {
            lifecycleScope.launch(Dispatchers.IO) {
                DeviceDirectoryManager.saveLocalSnapshot(this@SettingsActivity)
            }
            return
        }

        val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
        val broker = prefs.getString("BROKER", "") ?: ""
        val topic = prefs.getString("TOPIC", "") ?: ""
        val password = prefs.getString("PASSWORD", "") ?: ""
        if (broker.isBlank() || topic.isBlank() || password.isBlank()) {
            return
        }

        val intent = Intent(this, MqttSyncService::class.java).apply {
            action = MqttSyncService.ACTION_BROADCAST_DEVICE_PROFILE
            putExtra("MQTT_BROKER", broker)
            putExtra("MQTT_TOPIC", topic)
            putExtra("MQTT_PASSWORD", password)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
