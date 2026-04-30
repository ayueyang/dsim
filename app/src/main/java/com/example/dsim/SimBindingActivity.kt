package com.example.dsim

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.dsim.database.DsimDao
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimBindingActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnProbeAndBind: Button
    private var pendingPermissionAction: (() -> Unit)? = null

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (allGranted) {
            action?.invoke()
        } else {
            Toast.makeText(this, "缺少必要权限，无法探测 SIM", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SIM 绑定管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildContentView()
        btnProbeAndBind.setOnClickListener {
            checkPermissionAndAction { probeAndBindLocalSims() }
        }
        loadSimConfigs()
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

    private fun buildContentView() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F3F5F8"))
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scrollView.addView(container)

        tvStatus = TextView(this).apply {
            text = "管理本机物理 SIM 绑定。云端影子卡不会占用这里的本机卡槽名额。"
            setTextColor(Color.parseColor("#475569"))
            setTextSize(14f)
            setLineSpacing(0f, 1.18f)
        }
        btnProbeAndBind = Button(this).apply {
            text = "探测并绑定本机 SIM"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2457F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            }
        }
        container.addView(tvStatus)
        container.addView(btnProbeAndBind)
        setContentView(scrollView)
    }

    private fun loadSimConfigs(message: String? = null) {
        lifecycleScope.launch {
            val configs = withContext(Dispatchers.IO) {
                DsimDatabase.getDatabase(this@SimBindingActivity)
                    .dsimDao()
                    .getAllSimConfigs()
                    .filter { it.bindMode != "REMOTE_SHADOW" }
                    .sortedWith(compareByDescending<SimCardConfig> { it.isActive }.thenBy { it.slotIndex ?: 99 })
            }

            while (container.childCount > 2) {
                container.removeViewAt(2)
            }
            tvStatus.text = message ?: buildStatusText(configs)

            if (configs.isEmpty()) {
                container.addView(createEmptyCard())
                return@launch
            }

            configs.forEach { config ->
                container.addView(createConfigCard(config))
            }
        }
    }

    private fun buildStatusText(configs: List<SimCardConfig>): String {
        val activeCount = configs.count { it.isActive }
        return "本机已绑定 $activeCount 张活跃 SIM。最多建议同时保持 2 张活跃本机卡。"
    }

    private fun createEmptyCard(): LinearLayout {
        return createCard().apply {
            addView(createTitle("暂无本机 SIM 绑定"))
            addView(createMutedText("点击上方按钮探测本机 SIM，然后为卡槽填写手机号。"))
        }
    }

    private fun createConfigCard(config: SimCardConfig): LinearLayout {
        return createCard().apply {
            val row = LinearLayout(this@SimBindingActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleColumn = LinearLayout(this@SimBindingActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleColumn.addView(createTitle(config.phoneNumber.ifBlank { "未备注号码" }))
            titleColumn.addView(createMutedText(buildConfigDetail(config)))
            row.addView(titleColumn)

            val action = Button(this@SimBindingActivity).apply {
                text = if (config.isActive) "解绑" else "恢复"
                isAllCaps = false
                setTextColor(if (config.isActive) Color.parseColor("#8A1F2D") else Color.parseColor("#1F8A4D"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                setOnClickListener {
                    if (config.isActive) {
                        confirmUnbind(config)
                    } else {
                        restoreConfig(config)
                    }
                }
            }
            row.addView(action)
            addView(row)
        }
    }

    private fun buildConfigDetail(config: SimCardConfig): String {
        val mode = when (config.bindMode) {
            "ROOT_ICCID" -> "Root / ICCID"
            "NOROOT_DEVICE" -> "无 Root / 设备卡槽"
            else -> config.bindMode
        }
        val slot = config.slotIndex?.let { "卡槽 ${it + 1}" } ?: "卡槽未知"
        val subId = config.subscriptionId?.let { " · subId $it" }.orEmpty()
        val state = if (config.isActive) "活跃" else "已解绑"
        return "$state · $slot$subId · $mode\n${config.mappingKey}"
    }

    private fun confirmUnbind(config: SimCardConfig) {
        AlertDialog.Builder(this)
            .setTitle("解绑 SIM？")
            .setMessage("要解绑 ${config.phoneNumber.ifBlank { "这张卡" }} 吗？解绑后历史短信标签会保留。")
            .setPositiveButton("解绑") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = DsimDatabase.getDatabase(this@SimBindingActivity).dsimDao()
                    dao.unbindSimConfig(config.mappingKey)
                    refreshLocalDeviceSnapshot()
                    withContext(Dispatchers.Main) {
                        loadSimConfigs("已解绑 ${config.phoneNumber.ifBlank { config.mappingKey }}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restoreConfig(config: SimCardConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SimBindingActivity).dsimDao()
            val activeLocalConfigs = dao.getActiveSimConfigs().filter { it.bindMode != "REMOTE_SHADOW" }
            withContext(Dispatchers.Main) {
                if (activeLocalConfigs.size >= 2) {
                    AlertDialog.Builder(this@SimBindingActivity)
                        .setTitle("活跃卡已满")
                        .setMessage("当前已有 2 张活跃本机 SIM，请先解绑一张再恢复。")
                        .setPositiveButton("我知道了", null)
                        .show()
                    return@withContext
                }

                val simData = SimHardwareData(
                    mappingKey = config.mappingKey,
                    deviceId = config.deviceId.ifBlank {
                        HardwareProbeUtils.parseDeviceIdFromMappingKey(config.mappingKey)
                            ?: HardwareProbeUtils.getDeviceId(this@SimBindingActivity)
                    },
                    autoReadNumber = config.phoneNumber,
                    bindMode = config.bindMode,
                    slotIndex = config.slotIndex ?: 0,
                    subscriptionId = config.subscriptionId
                )
                showBindDialog(simData, dao)
            }
        }
    }

    private fun checkPermissionAndAction(action: () -> Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            action()
        } else {
            pendingPermissionAction = action
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun probeAndBindLocalSims() {
        btnProbeAndBind.isEnabled = false
        tvStatus.text = "正在探测本机 SIM..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HardwareProbeUtils.probeSimCards(this@SimBindingActivity)
                    DSimHardwareTester.runFullTest(this@SimBindingActivity)
                }
                checkAndBindSimCards()
            } finally {
                btnProbeAndBind.isEnabled = true
            }
        }
    }

    private fun checkAndBindSimCards() {
        val simDataList = HardwareProbeUtils.getStructuredSimInfo(this)
        if (simDataList.isEmpty()) {
            loadSimConfigs("未检测到活动 SIM。免 Root 模式下需要系统能读取到活动卡槽。")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@SimBindingActivity).dsimDao()
            val activeLocalConfigs = dao.getActiveSimConfigs().filter { it.bindMode != "REMOTE_SHADOW" }
            val hasActiveIccidMode = activeLocalConfigs.any { it.mappingKey.startsWith("ICCID_") }
            val hasActiveDevMode = activeLocalConfigs.any { it.mappingKey.startsWith("DEV_") }
            var allKnown = true

            for (simData in simDataList) {
                val existingConfig = findExistingLocalSimConfig(dao, simData)
                withContext(Dispatchers.Main) {
                    if (existingConfig == null || !existingConfig.isActive) {
                        allKnown = false
                        when {
                            activeLocalConfigs.size >= 2 -> {
                                AlertDialog.Builder(this@SimBindingActivity)
                                    .setTitle("活跃卡已满")
                                    .setMessage("当前已有 2 张活跃本机 SIM。请先解绑不需要的卡，再绑定新卡。")
                                    .setPositiveButton("我知道了", null)
                                    .show()
                            }

                            simData.bindMode == "ROOT_ICCID" && hasActiveDevMode -> {
                                showModeConflict("当前已有无 Root 绑定记录，请先解绑后再切换到 Root / ICCID 模式。")
                            }

                            simData.bindMode == "NOROOT_DEVICE" && hasActiveIccidMode -> {
                                showModeConflict("当前已有 Root / ICCID 绑定记录，请先解绑后再切换到无 Root 模式。")
                            }

                            else -> {
                                val suggested = existingConfig?.phoneNumber?.takeIf { it.isNotBlank() }
                                    ?: simData.autoReadNumber
                                showBindDialog(simData.copy(autoReadNumber = suggested), dao)
                            }
                        }
                    }
                }
            }

            if (allKnown) {
                withContext(Dispatchers.Main) {
                    loadSimConfigs("本机活动 SIM 都已绑定。")
                }
            }
        }
    }

    private fun showModeConflict(message: String) {
        AlertDialog.Builder(this)
            .setTitle("绑定模式冲突")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }

    private suspend fun findExistingLocalSimConfig(
        dao: DsimDao,
        simData: SimHardwareData
    ): SimCardConfig? {
        dao.getSimConfigByKey(simData.mappingKey)
            ?.takeIf { it.bindMode != "REMOTE_SHADOW" }
            ?.let { return it }

        simData.subscriptionId?.let { subscriptionId ->
            dao.getSimConfigByDeviceAndSubscriptionId(simData.deviceId, subscriptionId)
                ?.takeIf { it.bindMode != "REMOTE_SHADOW" }
                ?.let { return it }
        }

        return dao.getSimConfigByDeviceAndSlot(simData.deviceId, simData.slotIndex)
            ?.takeIf { it.bindMode != "REMOTE_SHADOW" }
    }

    private fun showBindDialog(simData: SimHardwareData, dao: DsimDao) {
        val input = EditText(this).apply {
            setText(GlobalNumberUtils.formatToE164(this@SimBindingActivity, simData.autoReadNumber))
            hint = "请输入本卡手机号"
            setSingleLine(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        val modeLabel = if (simData.bindMode == "ROOT_ICCID") {
            "Root / ICCID 模式"
        } else {
            "免 Root / 设备卡槽模式"
        }
        val message = "卡槽 ${simData.slotIndex + 1} · $modeLabel\n\nMappingKey:\n${simData.mappingKey}"

        AlertDialog.Builder(this)
            .setTitle("绑定本机 SIM")
            .setMessage(message)
            .setView(layout)
            .setPositiveButton("保存绑定") { _, _ ->
                val finalNumber = GlobalNumberUtils.formatToE164(
                    this@SimBindingActivity,
                    input.text.toString()
                )
                if (finalNumber.isBlank()) {
                    Toast.makeText(this, "手机号不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    dao.saveSimConfig(
                        SimCardConfig(
                            mappingKey = simData.mappingKey,
                            phoneNumber = finalNumber,
                            bindMode = simData.bindMode,
                            isActive = true,
                            deviceId = simData.deviceId,
                            subscriptionId = simData.subscriptionId,
                            slotIndex = simData.slotIndex
                        )
                    )
                    SimConfigIdentityManager.syncLocalConfigs(this@SimBindingActivity)
                    refreshLocalDeviceSnapshot()
                    withContext(Dispatchers.Main) {
                        loadSimConfigs("已绑定卡槽 ${simData.slotIndex + 1}：$finalNumber")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun refreshLocalDeviceSnapshot() {
        DeviceDirectoryManager.saveLocalSnapshot(this)
        if (!MqttSyncService.isConnected()) {
            return
        }
        val config = CloudSettingsManager.getConfig(this)
        ContextCompat.startForegroundService(
            this,
            Intent(this, MqttSyncService::class.java).apply {
                action = MqttSyncService.ACTION_BROADCAST_DEVICE_PROFILE
                putExtra("MQTT_BROKER", config.broker)
                putExtra("MQTT_TOPIC", config.topic)
                putExtra("MQTT_PASSWORD", config.password)
            }
        )
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun createTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(16f)
        }
    }

    private fun createMutedText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(13f)
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
