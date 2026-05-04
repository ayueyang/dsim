package com.example.dsim

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnOpenInbox: Button
    private lateinit var btnStartTest: Button
    private lateinit var btnRequestDefaultSms: Button
    private lateinit var btnInsertFakeSms: Button
    private lateinit var btnReadAllHistory: Button
    private lateinit var btnReadDualDbTest: Button
    private lateinit var btnManageSim: Button
    private lateinit var btnToggleRootMock: Button
    
    // Root 相关
    private lateinit var btnCheckRoot: Button
    private lateinit var tvRootStatus: TextView
    
    // 阶段三：云端 MQTT 模块 (零信任架构)
    private lateinit var etMqttBroker: EditText
    private lateinit var etMqttTopic: EditText
    private lateinit var etMqttPassword: EditText
    private lateinit var btnConnectCloud: Button

    private lateinit var progressBar: ProgressBar
    private lateinit var tvReport: TextView

    private val PREFS_NAME = "dsim_prefs"
    private val KEY_MQTT_BROKER = "last_mqtt_broker"
    private val KEY_MQTT_TOPIC = "last_mqtt_topic"
    private val SYNC_PREFS_NAME = "dSIM_SYNC_PREFS"
    private val KEY_HIGH_WATERMARK = "HIGH_WATERMARK"
    private var pendingPermissionAction: (() -> Unit)? = null

    // 1. 定义设置默认短信应用的回调
    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (DefaultSmsManager.isDefaultSmsApp(this)) {
            Toast.makeText(this, "设置成功：已接管默认短信应用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "设置失败：未获得默认短信身份", Toast.LENGTH_SHORT).show()
        }
    }

    // 所需的所有权限
    private val requiredPermissions = mutableListOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS,
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

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val action = pendingPermissionAction
        pendingPermissionAction = null

        if (allGranted) {
            lifecycleScope.launch(Dispatchers.IO) {
                SimConfigIdentityManager.syncLocalConfigs(this@MainActivity)
            }
            action?.invoke()
        } else {
            Toast.makeText(this, "缺少必要权限，当前操作未执行", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "测试功能"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<ImageButton>(R.id.btnBackMain).setOnClickListener {
            DsimNavigation.backToInboxOrFinish(this)
        }

        NotificationUtils.createNotificationChannel(this)

        val daemonIntent = android.content.Intent(this, MqttSyncService::class.java).apply {
            action = MqttSyncService.ACTION_INIT_DAEMON
        }
        androidx.core.content.ContextCompat.startForegroundService(this, daemonIntent)

        // 初始化视图
        btnOpenInbox = findViewById(R.id.btnOpenInbox)
        btnOpenInbox = findViewById(R.id.btnOpenInbox)
        btnStartTest = findViewById(R.id.btnStartTest)
        btnRequestDefaultSms = findViewById(R.id.btnRequestDefaultSms)
        btnInsertFakeSms = findViewById(R.id.btnInsertFakeSms)
        btnReadAllHistory = findViewById(R.id.btnReadAllHistory)
        btnReadDualDbTest = Button(this).apply {
            text = "双库只读测试"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        btnManageSim = findViewById(R.id.btnManageSim)
        btnToggleRootMock = findViewById(R.id.btnToggleRootMock)
        
        btnCheckRoot = findViewById(R.id.btnCheckRoot)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        
        etMqttBroker = findViewById(R.id.etMqttBroker)
        etMqttTopic = findViewById(R.id.etMqttTopic)
        etMqttPassword = findViewById(R.id.etMqttPassword)
        btnConnectCloud = findViewById(R.id.btnConnectCloud)
        
        progressBar = findViewById(R.id.progressBar)
        tvReport = findViewById(R.id.tvReport)
        (btnReadAllHistory.parent as? LinearLayout)?.let { testSection ->
            val insertIndex = testSection.indexOfChild(btnReadAllHistory) + 1
            testSection.addView(btnReadDualDbTest, insertIndex)
        }

        // 读取保存的配置
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etMqttBroker.setText(sharedPrefs.getString(KEY_MQTT_BROKER, "tcp://broker.emqx.io:1883"))
        etMqttTopic.setText(sharedPrefs.getString(KEY_MQTT_TOPIC, ""))
        
        // 读取 UI 表单记忆
        val uiPrefs = getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        etMqttBroker.setText(uiPrefs.getString("BROKER", "tcp://broker.emqx.io:1883"))
        etMqttTopic.setText(uiPrefs.getString("TOPIC", ""))
        etMqttPassword.setText(uiPrefs.getString("PASSWORD", ""))

        // 绑定事件
        btnOpenInbox.setOnClickListener {
            startActivity(Intent(this@MainActivity, SmsListActivity::class.java))
        }

        btnRequestDefaultSms.setOnClickListener {
            val intent = DefaultSmsManager.createRequestRoleIntent(this)
            if (intent != null) {
                defaultSmsLauncher.launch(intent)
            } else {
                Toast.makeText(this, "已经是默认短信应用", Toast.LENGTH_SHORT).show()
            }
        }

        btnInsertFakeSms.setOnClickListener {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val dao = com.example.dsim.database.DsimDatabase.getDatabase(this@MainActivity).dsimDao()
                
                val activeConfigs = dao.getActiveSimConfigs()
                val localConfigs = activeConfigs.filter { it.bindMode != "REMOTE_SHADOW" }
                val detectedPhysicalSims = HardwareProbeUtils.getStructuredSimInfo(this@MainActivity)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (localConfigs.isEmpty()) {
                        val hasPhysicalSim = detectedPhysicalSims.isNotEmpty()
                        val onlyShadowConfigs = activeConfigs.isNotEmpty()

                        if (hasPhysicalSim || onlyShadowConfigs) {
                            val message = if (hasPhysicalSim && onlyShadowConfigs) {
                                "检测到本机有物理 SIM，但当前只有云端影子卡映射，无法注入到本地卡。\n\n请先执行“硬件可行性探测”或进入“SIM 绑定管理”，把本机 SIM 绑定为本地卡。"
                            } else {
                                "检测到本机有物理 SIM，但还没有建立本地活跃绑定，所以暂时无法注入模拟短信。\n\n请先执行“硬件可行性探测”，完成本机 SIM 绑定。"
                            }

                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("暂时无法注入模拟短信")
                                .setMessage(message)
                                .setPositiveButton("去探测并绑定") { _, _ ->
                                    startSimBindingFlow()
                                }
                                .setNegativeButton("关闭", null)
                                .show()
                            return@withContext
                        }
                        android.widget.Toast.makeText(this@MainActivity, "❌ 无本地活跃物理卡，无法模拟", android.widget.Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    val displayList = localConfigs.map { 
                        "模拟接收卡: ${it.phoneNumber}\n[${if(it.mappingKey.startsWith("ICCID")) "极客Root" else "无Root"}]" 
                    }.toTypedArray()

                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("🎯 步骤 1：请选择接收此短信的 SIM 卡")
                        .setItems(displayList) { _, which ->
                            val selectedConfig = localConfigs[which]

                            val inputLayout = android.widget.LinearLayout(this@MainActivity).apply {
                                orientation = android.widget.LinearLayout.VERTICAL
                                setPadding(50, 40, 50, 0)
                            }
                            val etSender = android.widget.EditText(this@MainActivity).apply {
                                hint = "发件人号码 (默认 10086)"
                                inputType = android.text.InputType.TYPE_CLASS_PHONE
                            }
                            val etBody = android.widget.EditText(this@MainActivity).apply {
                                hint = "短信内容 (默认 测试数据)"
                            }
                            inputLayout.addView(etSender)
                            inputLayout.addView(etBody)

                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("✏️ 步骤 2：自定义幽灵短信内容")
                                .setView(inputLayout)
                                .setPositiveButton("写入并广播") { _, _ ->
                                    val mockSender = etSender.text.toString().takeIf { it.isNotBlank() } ?: "10086"
                                    val mockBody = etBody.text.toString().takeIf { it.isNotBlank() } ?: "【高精度模拟】这是一条通过 [${selectedConfig.phoneNumber}] 注入的短信！"

                                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val uuid = java.util.UUID.randomUUID().toString()

                                        val mockMsg = com.example.dsim.database.SmsMessage(
                                            uuid = uuid,
                                            address = mockSender,
                                            body = mockBody,
                                            timestamp = System.currentTimeMillis(),
                                            type = 1,
                                            status = 1,
                                            deviceId = com.example.dsim.HardwareProbeUtils.getDeviceId(this@MainActivity),
                                            simId = -1,
                                            iccid = null,
                                            mappingKey = selectedConfig.mappingKey
                                        )
                                        dao.insertMessage(mockMsg)

                                        val prefs = getSharedPreferences("dSIM_UI_PREFS", android.content.Context.MODE_PRIVATE)
                                        val password = prefs.getString("PASSWORD", "") ?: ""
                                        val topic = prefs.getString("TOPIC", "") ?: ""

                                        if (password.isNotBlank() && topic.isNotBlank() && com.example.dsim.MqttSyncService.globalMqttClient?.isConnected == true) {
                                            val payloadObj = com.example.dsim.SyncPayload(
                                                sms = mockMsg,
                                                remarkPhone = selectedConfig.phoneNumber,
                                                deviceName = com.example.dsim.DeviceNameManager.getDisplayName(this@MainActivity)
                                            )
                                            val payloadJson = com.google.gson.Gson().toJson(payloadObj)
                                            val encrypted = com.example.dsim.DsimCryptoUtils.encryptMessage(payloadJson, password)
                                            
                                            val mqttMsg = org.eclipse.paho.client.mqttv3.MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                                            com.example.dsim.MqttSyncService.globalMqttClient?.publish(topic, mqttMsg)
                                        }

                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            tvReport.append("\n✅ 已向 [${selectedConfig.phoneNumber}] 注入发件人为 $mockSender 的短信并全网广播！")
                                        }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        btnReadAllHistory.setOnClickListener {
            checkPermissionAndAction {
                tvReport.text = "正在全量拉取历史短信..."
                lifecycleScope.launch {
                    setLoading(true)
                    val result = SmsDatabaseTester.readAllHistoricalSms(this@MainActivity)
                    tvReport.text = result
                    setLoading(false)
                }
            }
        }

        // 云端连接逻辑 (零信任架构版)
        btnReadDualDbTest.setOnClickListener {
            checkPermissionAndAction {
                tvReport.text = "正在读取系统库和 App 库..."
                lifecycleScope.launch {
                    setLoading(true)
                    val result = SmsDatabaseTester.readSystemAndAppDbSummary(this@MainActivity)
                    tvReport.text = result
                    setLoading(false)
                }
            }
        }

        val switchAutoConnect = findViewById<android.widget.Switch>(R.id.switchAutoConnect)
        switchAutoConnect.isChecked = CloudSettingsManager.isAutoConnectEnabled(this)
        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            CloudSettingsManager.setAutoConnectEnabled(this, isChecked)
        }

        lifecycleScope.launch {
            MqttSyncService.connectionStateFlow.collect { isConnected ->
                if (isConnected) {
                    btnConnectCloud.text = "✅ 隧道已连接 (点击断开)"
                    btnConnectCloud.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    etMqttBroker.isEnabled = false
                    etMqttTopic.isEnabled = false
                    etMqttPassword.isEnabled = false
                    btnConnectCloud.setOnClickListener {
                        showManualDisconnectDialog()
                    }
                } else {
                    btnConnectCloud.text = "接入云端频道"
                    btnConnectCloud.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                    etMqttBroker.isEnabled = true
                    etMqttTopic.isEnabled = true
                    etMqttPassword.isEnabled = true
                    btnConnectCloud.setOnClickListener {
                        val broker = etMqttBroker.text.toString().trim()
                        val topic = etMqttTopic.text.toString().trim()
                        val password = etMqttPassword.text.toString().trim()
                        
                        if (topic.isBlank() || password.isBlank()) {
                            tvReport.append("\n❌ 频道和密码不能为空！")
                            return@setOnClickListener
                        }
                        
                        uiPrefs.edit()
                            .putString("BROKER", broker)
                            .putString("TOPIC", topic)
                            .putString("PASSWORD", password)
                            .apply()
                        
                        tvReport.append("\n正在启动工业级后台保活隧道: $topic ...")
                        val serviceIntent = Intent(this@MainActivity, MqttSyncService::class.java).apply {
                            action = MqttSyncService.ACTION_CONNECT
                            putExtra("MQTT_BROKER", broker)
                            putExtra("MQTT_TOPIC", topic)
                            putExtra("MQTT_PASSWORD", password)
                        }
                        androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                        
                        sharedPrefs.edit()
                            .putString(KEY_MQTT_BROKER, broker)
                            .putString(KEY_MQTT_TOPIC, topic)
                            .apply()
                    }
                }
            }
        }

        // 增量同步与全量补发双引擎
        val btnPushIncremental = findViewById<android.widget.Button>(R.id.btnPushIncremental)
        val btnPushFullHistory = findViewById<android.widget.Button>(R.id.btnPushFullHistory)
        
        fun executeSync(isIncremental: Boolean) {
            val currentTopic = etMqttTopic.text.toString().trim()
            val currentPassword = etMqttPassword.text.toString().trim()
            
            val client = MqttSyncService.globalMqttClient
            if (client == null || !client.isConnected) {
                tvReport.append("\n❌ 同步失败：底层 MQTT 隧道未连接，请先点击蓝色【接入】按钮！")
                return
            }
            if (currentTopic.isBlank() || currentPassword.isBlank()) {
                tvReport.append("\n❌ 同步失败：频道和密码不能为空！")
                return
            }

            val prefs = getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            val lastWatermark = prefs.getLong(KEY_HIGH_WATERMARK, 0L)
            
            android.util.Log.d("dSIM_SyncBug", "开始同步: isIncremental=$isIncremental, watermark=$lastWatermark")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dao = DsimDatabase.getDatabase(this@MainActivity).dsimDao()
                    val configs = dao.getAllSimConfigs()
                    
                    android.util.Log.d("dSIM_SyncBug", "SIM配置数量: ${configs.size}")
                    
                    val messagesToSync = if (isIncremental) {
                        dao.getMessagesAfterWatermark(lastWatermark)
                    } else {
                        dao.getAllSmsMessagesAsc()
                    }
                    
                    android.util.Log.d("dSIM_SyncBug", "查询到短信数量: ${messagesToSync.size}")

                    if (messagesToSync.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            tvReport.append(
                                if (isIncremental) {
                                    "\n✅ 增量检查：水位线之上无新数据。"
                                } else {
                                    "\n✅ 私有数据库为空。云端同步按钮不会读取本机系统短信；如需重建，请去【正式设置】->【系统历史短信】，用【开始导入历史短信】处理。若刚清空过私有库，可先点【重置本机读取进度】。"
                                }
                            )
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        tvReport.append("\n🚀 正在向云端发射 ${messagesToSync.size} 条数据...")
                    }

                    var successCount = 0
                    var newWatermark = lastWatermark

                    for ((index, msg) in messagesToSync.withIndex()) {
                        try {
                            val targetPhone = configs.firstOrNull { it.mappingKey == msg.mappingKey }?.phoneNumber ?: "未知云端号码"
                            val payload = SyncPayload(
                                sms = msg,
                                remarkPhone = targetPhone,
                                deviceName = DeviceNameManager.getDisplayName(this@MainActivity)
                            )
                            val json = com.google.gson.Gson().toJson(payload)
                            val encryptedBase64 = DsimCryptoUtils.encryptMessage(json, currentPassword)
                            
                            if (encryptedBase64 != "ENCRYPTION_ERROR") {
                                val mqttMsg = org.eclipse.paho.client.mqttv3.MqttMessage(encryptedBase64.toByteArray(Charsets.UTF_8))
                                mqttMsg.qos = 1
                                client.publish(currentTopic, mqttMsg)
                                
                                successCount++
                                if (msg.timestamp > newWatermark) {
                                    newWatermark = msg.timestamp
                                }
                                android.util.Log.d("dSIM_SyncBug", "[$index] 发送成功: ${msg.address}")
                            } else {
                                android.util.Log.e("dSIM_SyncBug", "[$index] 加密失败: ${msg.address}")
                            }
                            kotlinx.coroutines.delay(50)
                        } catch (e: Exception) {
                            android.util.Log.e("dSIM_SyncBug", "[$index] 单条发送异常: ${e.message}", e)
                        }
                    }
                    
                    prefs.edit().putLong(KEY_HIGH_WATERMARK, newWatermark).apply()
                    
                    withContext(Dispatchers.Main) {
                        tvReport.append("\n✅ 发射完毕！成功发送 $successCount 条，水位线已推高。")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("dSIM_SyncBug", "历史同步发生致命异常", e)
                    withContext(Dispatchers.Main) {
                        tvReport.append("\n❌ 历史同步崩溃: ${e.stackTraceToString().take(300)}")
                    }
                }
            }
        }

        btnPushIncremental.setOnClickListener { executeSync(isIncremental = true) }
        
        btnPushFullHistory.setOnClickListener { 
            AlertDialog.Builder(this)
                .setTitle("警告：全量补发")
                .setMessage("全量补发只会重发私有数据库里的短信，不会读取本机系统短信。确定要执行吗？")
                .setPositiveButton("强制全量") { _, _ -> executeSync(isIncremental = false) }
                .setNegativeButton("取消", null).show()
        }

        btnCheckRoot.setOnClickListener { performRootCheck(manualRequest = true) }
        btnStartTest.setOnClickListener { checkPermissionAndAction { runHardwareTest() } }
        
        btnManageSim.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = DsimDatabase.getDatabase(this@MainActivity).dsimDao()
                val configs = dao.getAllSimConfigs()

                withContext(Dispatchers.Main) {
                    if (configs.isEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("暂无 SIM 绑定记录")
                            .setMessage("当前数据库里还没有任何本地绑定记录。\n\n要不要现在开始探测本机 SIM 并进入绑定流程？")
                            .setPositiveButton("开始绑定") { _, _ ->
                                startSimBindingFlow()
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                        return@withContext
                    }

                    val scrollView = android.widget.ScrollView(this@MainActivity)
                    val container = android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(50, 20, 50, 20)
                    }
                    scrollView.addView(container)

                    var manageDialog: android.app.AlertDialog? = null

                    for (config in configs) {
                        val itemLayout = android.widget.LinearLayout(this@MainActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            setPadding(0, 30, 0, 30)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val textLayout = android.widget.LinearLayout(this@MainActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val tvPhone = android.widget.TextView(this@MainActivity).apply {
                            text = if (config.phoneNumber.isNotBlank()) config.phoneNumber else "未备注号码"
                            textSize = 18f
                            setTextColor(if (config.isActive) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#BBBBBB"))
                            setTypeface(null, if (config.isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        }

                        val tvIccid = android.widget.TextView(this@MainActivity).apply {
                            text = config.mappingKey
                            textSize = 12f
                            setTextColor(android.graphics.Color.parseColor("#888888"))
                            setPadding(0, 8, 0, 0)
                        }
                        textLayout.addView(tvPhone)
                        textLayout.addView(tvIccid)

                        val btnAction = android.widget.Button(this@MainActivity).apply {
                            if (config.isActive) {
                                text = "解绑"
                                setTextColor(android.graphics.Color.RED)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setOnClickListener {
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("确认移除")
                                        .setMessage("要解绑 ${config.phoneNumber} 吗？解绑后历史短信标签将保留。")
                                        .setPositiveButton("解绑") { _, _ ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                dao.unbindSimConfig(config.mappingKey)
                                                withContext(Dispatchers.Main) {
                                                    tvReport.append("\n\n【管理系统】软解绑成功: ${config.phoneNumber}")
                                                    manageDialog?.dismiss()
                                                }
                                            }
                                        }
                                        .setNegativeButton("取消", null).show()
                                }
                            } else {
                                text = "恢复"
                                setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setOnClickListener {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val activeConfigs = dao.getActiveSimConfigs()
                                        val hasActiveIccidMode = activeConfigs.any { it.mappingKey.startsWith("ICCID_") }
                                        val hasActiveDevMode = activeConfigs.any { it.mappingKey.startsWith("DEV_") }
                                        val isTargetRootKey = config.mappingKey.startsWith("ICCID_")

                                        withContext(Dispatchers.Main) {
                                            if (activeConfigs.size >= 2) {
                                                android.app.AlertDialog.Builder(this@MainActivity)
                                                    .setTitle("名额已满")
                                                    .setMessage("当前已有 2 张活跃 SIM 卡，无法恢复此卡。\n请先解绑其他卡片。")
                                                    .setPositiveButton("我知道了", null).show()
                                                return@withContext
                                            }

                                            if (isTargetRootKey && hasActiveDevMode) {
                                                android.app.AlertDialog.Builder(this@MainActivity)
                                                    .setTitle("⚠️ 模式冲突")
                                                    .setMessage("此卡为 Root(ICCID) 模式记录，但系统当前正运行在无 Root(DEV) 模式。\n无法恢复此记录。")
                                                    .setPositiveButton("关闭", null).show()
                                                return@withContext
                                            }
                                            if (!isTargetRootKey && hasActiveIccidMode) {
                                                android.app.AlertDialog.Builder(this@MainActivity)
                                                    .setTitle("⚠️ 模式冲突")
                                                    .setMessage("此卡为无 Root(DEV) 模式记录，但系统当前正运行在 Root(ICCID) 模式。\n无法恢复此记录。")
                                                    .setPositiveButton("关闭", null).show()
                                                return@withContext
                                            }

                                            manageDialog?.dismiss()
                                            val mockSimData = com.example.dsim.SimHardwareData(
                                                mappingKey = config.mappingKey,
                                                deviceId = config.deviceId.ifBlank {
                                                    HardwareProbeUtils.parseDeviceIdFromMappingKey(config.mappingKey)
                                                        ?: HardwareProbeUtils.getDeviceId(this@MainActivity)
                                                },
                                                autoReadNumber = config.phoneNumber,
                                                bindMode = if (isTargetRootKey) "ROOT_ICCID" else "NOROOT_DEVICE",
                                                slotIndex = config.slotIndex ?: 0,
                                                subscriptionId = config.subscriptionId
                                            )
                                            showBindDialog(mockSimData, dao)
                                        }
                                    }
                                }
                            }
                        }
                        itemLayout.addView(textLayout)
                        itemLayout.addView(btnAction)

                        val divider = android.view.View(this@MainActivity).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2)
                            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
                        }
                        container.addView(itemLayout)
                        container.addView(divider)
                    }
                    manageDialog = android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("SIM 卡绑定管理")
                        .setView(scrollView)
                        .setNegativeButton("关闭", null).show()
                }
            }
        }
        
        btnToggleRootMock.setOnClickListener {
            com.example.dsim.HardwareProbeUtils.isMockNoRootMode = !com.example.dsim.HardwareProbeUtils.isMockNoRootMode
            
            if (com.example.dsim.HardwareProbeUtils.isMockNoRootMode) {
                btnToggleRootMock.text = "恢复 Root 探测 (结束测试)"
                btnToggleRootMock.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                tvReport.append("\n\n【测试模式】已强行关闭 Root 探测！\n请先解绑旧卡，然后点击[硬件可行性探测]，观察是否会生成 DEV_xxx 格式的键值。")
            } else {
                btnToggleRootMock.text = "关闭 Root 探测 (开启无 Root 测试)"
                btnToggleRootMock.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
                tvReport.append("\n\n【测试模式】已恢复真实的 Root 探测环境。")
            }
        }
        
        val btnClearSmsDb = findViewById<android.widget.Button>(R.id.btnClearSmsDb)
        btnClearSmsDb.setOnClickListener {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("⚠️ 高危操作确认")
                .setMessage("确定要清空私有数据库中的【所有短信记录】吗？\n\n注：已绑定的 SIM 卡花名册配置将不受影响。")
                .setPositiveButton("彻底清空") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = com.example.dsim.database.DsimDatabase.getDatabase(this@MainActivity).dsimDao()
                        dao.clearAllSmsMessages()
                        SystemSmsHistoryImporter.resetImportProgress(this@MainActivity, clearLastImportAt = true)
                        getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putLong(KEY_HIGH_WATERMARK, 0L)
                            .apply()
                        SystemHistoryImportService.clearSnapshot()
                        
                        withContext(Dispatchers.Main) {
                            tvReport.append("\n\n【数据清理完毕】\n私有数据库(sms_messages)已清空，历史读取进度和增量同步水位线也已重置。\n现在可以去【正式设置】->【系统历史短信】重新导入本机历史短信。")
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        
        val btnToggleMute = findViewById<android.widget.Button>(R.id.btnToggleMute)
        val mutePrefs = getSharedPreferences("dSIM_UI_PREFS", android.content.Context.MODE_PRIVATE)
        
        fun updateMuteButtonUi() {
            val isMuted = mutePrefs.getBoolean("IS_MUTED", false)
            if (isMuted) {
                btnToggleMute.text = "通知状态：🔇 静音防打扰"
                btnToggleMute.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#9E9E9E"))
            } else {
                btnToggleMute.text = "通知状态：🔔 响铃与弹窗"
                btnToggleMute.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2196F3"))
            }
        }
        updateMuteButtonUi()
        
        btnToggleMute.setOnClickListener {
            val currentMute = mutePrefs.getBoolean("IS_MUTED", false)
            mutePrefs.edit().putBoolean("IS_MUTED", !currentMute).apply()
            updateMuteButtonUi()
            val status = if (!currentMute) "已开启静音模式，通知将无声显示。" else "已开启响铃模式，将允许弹窗与震动。"
            android.widget.Toast.makeText(this, status, android.widget.Toast.LENGTH_SHORT).show()
        }
        
        val btnTestNotification = findViewById<android.widget.Button>(R.id.btnTestNotification)
        btnTestNotification.setOnClickListener {
            val fakeSms = com.example.dsim.database.SmsMessage(
                uuid = java.util.UUID.randomUUID().toString(),
                address = "10086",
                body = "【系统探针】这是一条本地模拟测试短信。如果您能看到横幅并听到声音，说明 MIUI 通知通道已完全打通！",
                timestamp = System.currentTimeMillis(),
                type = 1,
                status = 1,
                deviceId = "LOCAL_TEST",
                simId = 0,
                iccid = null,
                mappingKey = "TEST_KEY"
            )
            
            val testPrefs = getSharedPreferences("dSIM_UI_PREFS", android.content.Context.MODE_PRIVATE)
            val isMuted = testPrefs.getBoolean("IS_MUTED", false)
            if (isMuted) {
                android.widget.Toast.makeText(this, "当前为静音模式，系统将仅进行无声静默推送", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "正在触发响铃通知，请注意声音和顶部弹窗！", android.widget.Toast.LENGTH_SHORT).show()
            }

            NotificationUtils.showNewMessageNotification(this, fakeSms, "系统测试员")
        }

        val btnRadar = findViewById<android.widget.Button>(R.id.btnRadar)
        btnRadar.setOnClickListener {
            startActivity(android.content.Intent(this@MainActivity, DeviceManagerActivity::class.java))
        }

        performRootCheck(manualRequest = false)
        requestMissingPermissionsIfNeeded()
        lifecycleScope.launch(Dispatchers.IO) {
            SimConfigIdentityManager.syncLocalConfigs(this@MainActivity)
        }
    }

    private fun performRootCheck(manualRequest: Boolean) {
        lifecycleScope.launch {
            if (manualRequest) tvRootStatus.text = "正在请求..."
            val hasRoot = DSimHardwareTester.checkAndRequestRoot()
            tvRootStatus.text = "Root状态: ${if (hasRoot) "已获取" else "未获取"}"
            tvRootStatus.setTextColor(if (hasRoot) Color.parseColor("#00AA00") else Color.RED)
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

    private fun requestMissingPermissionsIfNeeded() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startSimBindingFlow() {
        Toast.makeText(this, "开始探测本机 SIM 并准备进入绑定流程…", Toast.LENGTH_SHORT).show()
        tvReport.text = "正在探测本机 SIM 并准备进入绑定流程..."
        checkPermissionAndAction { runHardwareTest() }
    }

    private fun runHardwareTest() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val probeResult = HardwareProbeUtils.probeSimCards(this@MainActivity)
                val testResult = DSimHardwareTester.runFullTest(this@MainActivity)
                tvReport.text = probeResult + "\n\n" + testResult
                
                checkAndBindSimCards()
            } 
            finally { setLoading(false) }
        }
    }

    private fun checkAndBindSimCards() {
        val simDataList = HardwareProbeUtils.getStructuredSimInfo(this)
        if (simDataList.isEmpty()) {
            tvReport.append("\n【未检测到活动 SIM 卡】")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DsimDatabase.getDatabase(this@MainActivity).dsimDao()
            
            val activeConfigs = dao.getActiveSimConfigs()
            
            val hasActiveIccidMode = activeConfigs.any { it.mappingKey.startsWith("ICCID_") }
            val hasActiveDevMode = activeConfigs.any { it.mappingKey.startsWith("DEV_") }

            for (simData in simDataList) {
                val existingConfig = findExistingSimConfig(dao, simData)
                
                withContext(Dispatchers.Main) {
                    if (existingConfig == null || !existingConfig.isActive) {

                        if (activeConfigs.size >= 2) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("名额已满")
                                .setMessage("dSIM 控制台目前仅支持管理 2 条活跃的 SIM 卡通道。\n请先前往「SIM 卡绑定管理」中解绑不需要的卡片。")
                                .setPositiveButton("前往解绑") { _, _ ->
                                    findViewById<android.widget.Button>(R.id.btnManageSim).performClick()
                                }
                                .setNegativeButton("取消", null).show()
                            return@withContext
                        }

                        if (simData.bindMode == "ROOT_ICCID" && hasActiveDevMode) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("⚠️ 模式冲突")
                                .setMessage("当前系统正运行在无 Root (DEV) 模式。为了避免溯源混乱，我们已拦截 Root 探测。\n请先解绑所有卡片，点击橙色按钮「恢复探测模式」，才能在不同模式间切换。")
                                .setPositiveButton("关闭", null).show()
                            return@withContext
                        }
                        if (simData.bindMode == "NOROOT_DEVICE" && hasActiveIccidMode) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("⚠️ 模式冲突")
                                .setMessage("当前系统正完美运行在极客 Root (ICCID) 模式。为了避免溯源混乱，我们已拦截 DEV 探测。\n请点击橙色按钮「恢复探测模式」，或者先解绑所有卡片，才能在不同模式间切换。")
                                .setPositiveButton("关闭", null).show()
                            return@withContext
                        }

                        val suggestNumber = if (existingConfig != null && existingConfig.phoneNumber.isNotBlank()) {
                            existingConfig.phoneNumber
                        } else {
                            simData.autoReadNumber
                        }
                        val finalSimData = simData.copy(autoReadNumber = suggestNumber)
                        
                        showBindDialog(finalSimData, dao)
                    } else {
                        tvReport.append("\n卡 ${simData.slotIndex + 1} 已绑定: ${existingConfig.phoneNumber} (${existingConfig.bindMode})")
                    }
                }
            }
        }
    }

    private fun showBindDialog(simData: SimHardwareData, dao: com.example.dsim.database.DsimDao) {
        val normalizedAutoNumber = GlobalNumberUtils.formatToE164(this, simData.autoReadNumber)
        
        val input = EditText(this)
        input.setText(normalizedAutoNumber)
        input.hint = "请输入本卡手机号"

        val layout = LinearLayout(this)
        layout.setPadding(50, 20, 50, 20)
        layout.addView(input)

        val warningMsg = if (simData.bindMode == "NOROOT_DEVICE") {
            "\n\n⚠️ 【无 Root 模式运行警告】\n当前系统受限，已降级采用「设备ID + 订阅ID(SUBID)」绑定（单设备内防串号）。\n" +
            "• 风险：若手机【恢复出厂】或【刷机】，设备ID重置，绑定关系将永久失效！\n" +
            "💡 强烈建议：为本应用授予 Root 权限，以解锁基于 ICCID 的完美跨设备无缝漫游功能。"
        } else {
            "\n\n✅ 【极客 Root 模式 (完美追踪)】\n已成功提取 SIM 卡终身物理序列号 (ICCID)。\n" +
            "• 优势：您可以将此卡【随意插拔更换至任何备用机】，系统均能瞬间精准溯源，永不丢失备注！\n" +
            "• 注意：若您前往营业厅【挂失补办新卡】，物理芯片变更会导致 ICCID 改变，届时系统会将其视为新卡，仅需重新确认一次即可。"
        }

        val dialogMessage = "识别到的系统号码可能不准确或为空，请手动确认/修改：\n\n🔑 MappingKey:\n${simData.mappingKey}$warningMsg"

        AlertDialog.Builder(this)
            .setTitle("发现未绑定 SIM 卡 (卡槽 ${simData.slotIndex + 1})")
            .setMessage(dialogMessage)
            .setView(layout)
            .setCancelable(true)
            .setPositiveButton("保存绑定") { _, _ ->
                val userInputNumber = input.text.toString()
                val finalNormalizedNumber = GlobalNumberUtils.formatToE164(this@MainActivity, userInputNumber)

                if (finalNormalizedNumber.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        dao.saveSimConfig(
                            SimCardConfig(
                                mappingKey = simData.mappingKey,
                                phoneNumber = finalNormalizedNumber,
                                bindMode = simData.bindMode,
                                isActive = true,
                                deviceId = simData.deviceId,
                                subscriptionId = simData.subscriptionId,
                                slotIndex = simData.slotIndex
                            )
                        )
                        withContext(Dispatchers.Main) {
                            tvReport.append("\n绑定成功！卡 ${simData.slotIndex + 1} -> $finalNormalizedNumber")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun findExistingSimConfig(
        dao: com.example.dsim.database.DsimDao,
        simData: SimHardwareData
    ): SimCardConfig? {
        dao.getSimConfigByKey(simData.mappingKey)?.let { return it }

        simData.subscriptionId?.let { subscriptionId ->
            dao.getSimConfigByDeviceAndSubscriptionId(simData.deviceId, subscriptionId)?.let { return it }
        }

        dao.getSimConfigByDeviceAndSlot(simData.deviceId, simData.slotIndex)?.let { return it }
        return null
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

    private fun showManualDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("手动断开云端？")
            .setMessage("手动断开后，本次运行期间不会自动重连。\n\n你可以再次点击连接恢复，或者重启软件、手机后再按自动连接设置接入。")
            .setPositiveButton("确认断开") { _, _ ->
                val intent = Intent(this@MainActivity, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_DISCONNECT
                }
                androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, intent)
                Toast.makeText(
                    this,
                    "已手动断开，本次不会自动重连",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        val buttons = arrayOf(btnStartTest, btnInsertFakeSms, btnReadAllHistory, btnReadDualDbTest, btnConnectCloud)
        buttons.forEach { it.isEnabled = !isLoading }
    }
}
