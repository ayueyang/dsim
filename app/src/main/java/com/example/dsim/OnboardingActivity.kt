package com.example.dsim

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN_HOME_ON_FINISH = "RETURN_HOME_ON_FINISH"
        private const val TOTAL_STEPS = 6
        private const val ANTI_FRAUD_COUNTDOWN_MS = 8_000L
        private const val STATE_CURRENT_STEP = "STATE_CURRENT_STEP"
        private const val STATE_ANTI_FRAUD_DEADLINE_MS = "STATE_ANTI_FRAUD_DEADLINE_MS"
    }

    private lateinit var btnBackOnboarding: ImageButton
    private lateinit var tvOnboardingModeBadge: TextView
    private lateinit var tvOnboardingStep: TextView
    private lateinit var tvOnboardingTitle: TextView
    private lateinit var tvOnboardingBody: TextView
    private lateinit var btnOnboardingSecondary: Button
    private lateinit var btnOnboardingPrimary: Button

    private lateinit var stepAntiFraud: LinearLayout
    private lateinit var stepPermissions: LinearLayout
    private lateinit var stepDefaultSms: LinearLayout
    private lateinit var stepCloudConfig: LinearLayout
    private lateinit var stepSimBinding: LinearLayout
    private lateinit var stepFinish: LinearLayout

    private lateinit var tvAntiFraudStatus: TextView
    private lateinit var cardModeBidirectional: MaterialCardView
    private lateinit var cardModeReceiveOnly: MaterialCardView
    private lateinit var cardModeForwardOnly: MaterialCardView
    private lateinit var cardModeLocalOnly: MaterialCardView
    private lateinit var btnAcknowledgeAntiFraud: Button

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvPermissionDetail: TextView
    private lateinit var btnRequestCorePermissions: Button

    private lateinit var tvDefaultSmsStepStatus: TextView
    private lateinit var btnRequestDefaultSmsStep: Button

    private lateinit var tvCloudStepHint: TextView
    private lateinit var etOnboardingBroker: EditText
    private lateinit var etOnboardingTopic: EditText
    private lateinit var etOnboardingPassword: EditText
    private lateinit var btnSaveCloudConfigStep: Button
    private lateinit var tvCloudConfigStatus: TextView

    private lateinit var tvSimBindingStepStatus: TextView
    private lateinit var btnOpenSimBindingStep: Button

    private lateinit var tvFinishSummary: TextView

    private lateinit var stepViews: List<View>

    private var currentStep = 0
    private var returnHomeOnFinish = false
    private var antiFraudCountdownDeadlineMs = 0L
    private var antiFraudCountdownJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStatuses()
        if (CorePermissionHelper.hasAllPermissions(this)) {
            Toast.makeText(this, "基础权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "还有部分权限未授权，你也可以稍后继续补完", Toast.LENGTH_SHORT).show()
        }
    }

    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatuses()
        Toast.makeText(
            this,
            if (DefaultSmsManager.isDefaultSmsApp(this)) {
                "已设为默认短信应用"
            } else {
                "还没有获得默认短信身份"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnHomeOnFinish = intent.getBooleanExtra(EXTRA_RETURN_HOME_ON_FINISH, false)
        currentStep = savedInstanceState?.getInt(STATE_CURRENT_STEP, 0) ?: 0
        configureSystemBars()
        setContentView(R.layout.activity_onboarding)
        NotificationUtils.createNotificationChannel(this)

        antiFraudCountdownDeadlineMs = if (OnboardingStateStore.isAntiFraudAcknowledged(this)) {
            0L
        } else {
            val restoredDeadline = savedInstanceState?.getLong(STATE_ANTI_FRAUD_DEADLINE_MS, 0L) ?: 0L
            restoredDeadline.takeIf { it > 0L } ?: (SystemClock.elapsedRealtime() + ANTI_FRAUD_COUNTDOWN_MS)
        }

        bindViews()
        bindActions()
        seedCloudInputs()
        refreshStatuses()
        updateStepUi()
        syncAntiFraudCountdown()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        updateStepUi()
        syncAntiFraudCountdown()
    }

    override fun onPause() {
        stopAntiFraudCountdown()
        super.onPause()
    }

    override fun onDestroy() {
        stopAntiFraudCountdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_STEP, currentStep)
        outState.putLong(STATE_ANTI_FRAUD_DEADLINE_MS, antiFraudCountdownDeadlineMs)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (currentStep > 0) {
            currentStep -= 1
            refreshStatuses()
            updateStepUi()
            syncAntiFraudCountdown()
            return
        }

        if (!OnboardingStateStore.isAntiFraudAcknowledged(this)) {
            finish()
            return
        }
        exitOnboarding()
    }

    private fun configureSystemBars() {
        window.statusBarColor = Color.parseColor("#123B48")
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun bindViews() {
        btnBackOnboarding = findViewById(R.id.btnBackOnboarding)
        tvOnboardingModeBadge = findViewById(R.id.tvOnboardingModeBadge)
        tvOnboardingStep = findViewById(R.id.tvOnboardingStep)
        tvOnboardingTitle = findViewById(R.id.tvOnboardingTitle)
        tvOnboardingBody = findViewById(R.id.tvOnboardingBody)
        btnOnboardingSecondary = findViewById(R.id.btnOnboardingSecondary)
        btnOnboardingPrimary = findViewById(R.id.btnOnboardingPrimary)

        stepAntiFraud = findViewById(R.id.stepAntiFraud)
        stepPermissions = findViewById(R.id.stepPermissions)
        stepDefaultSms = findViewById(R.id.stepDefaultSms)
        stepCloudConfig = findViewById(R.id.stepCloudConfig)
        stepSimBinding = findViewById(R.id.stepSimBinding)
        stepFinish = findViewById(R.id.stepFinish)
        stepViews = listOf(
            stepAntiFraud,
            stepPermissions,
            stepDefaultSms,
            stepCloudConfig,
            stepSimBinding,
            stepFinish
        )

        tvAntiFraudStatus = findViewById(R.id.tvAntiFraudStatus)
        cardModeBidirectional = findViewById(R.id.cardModeBidirectional)
        cardModeReceiveOnly = findViewById(R.id.cardModeReceiveOnly)
        cardModeForwardOnly = findViewById(R.id.cardModeForwardOnly)
        cardModeLocalOnly = findViewById(R.id.cardModeLocalOnly)
        btnAcknowledgeAntiFraud = findViewById(R.id.btnAcknowledgeAntiFraud)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvPermissionDetail = findViewById(R.id.tvPermissionDetail)
        btnRequestCorePermissions = findViewById(R.id.btnRequestCorePermissions)

        tvDefaultSmsStepStatus = findViewById(R.id.tvDefaultSmsStepStatus)
        btnRequestDefaultSmsStep = findViewById(R.id.btnRequestDefaultSmsStep)

        tvCloudStepHint = findViewById(R.id.tvCloudStepHint)
        etOnboardingBroker = findViewById(R.id.etOnboardingBroker)
        etOnboardingTopic = findViewById(R.id.etOnboardingTopic)
        etOnboardingPassword = findViewById(R.id.etOnboardingPassword)
        btnSaveCloudConfigStep = findViewById(R.id.btnSaveCloudConfigStep)
        tvCloudConfigStatus = findViewById(R.id.tvCloudConfigStatus)

        tvSimBindingStepStatus = findViewById(R.id.tvSimBindingStepStatus)
        btnOpenSimBindingStep = findViewById(R.id.btnOpenSimBindingStep)

        tvFinishSummary = findViewById(R.id.tvFinishSummary)
    }

    private fun bindActions() {
        btnBackOnboarding.setOnClickListener { onBackPressed() }

        cardModeBidirectional.setOnClickListener { handleModeSelection(UsageMode.BIDIRECTIONAL_SYNC) }
        cardModeReceiveOnly.setOnClickListener { handleModeSelection(UsageMode.RECEIVE_ONLY) }
        cardModeForwardOnly.setOnClickListener { handleModeSelection(UsageMode.FORWARD_ONLY) }
        cardModeLocalOnly.setOnClickListener { handleModeSelection(UsageMode.LOCAL_ONLY) }

        btnAcknowledgeAntiFraud.setOnClickListener {
            if (hasAntiFraudCountdownActive()) {
                Toast.makeText(this, "请先完整阅读风险提醒", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            OnboardingStateStore.setAntiFraudAcknowledged(this, true)
            antiFraudCountdownDeadlineMs = 0L
            stopAntiFraudCountdown()
            refreshStatuses()
            updateStepUi()
            Toast.makeText(this, "已记录安全确认，你之后也可以在设置里重新查看", Toast.LENGTH_SHORT).show()
        }

        btnRequestCorePermissions.setOnClickListener {
            val missing = CorePermissionHelper.missingPermissions(this)
            if (missing.isEmpty()) {
                Toast.makeText(this, "基础权限已经齐了", Toast.LENGTH_SHORT).show()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        btnRequestDefaultSmsStep.setOnClickListener {
            val intent = DefaultSmsManager.createRequestRoleIntent(this)
            if (intent == null) {
                refreshStatuses()
                Toast.makeText(this, "当前已经是默认短信应用", Toast.LENGTH_SHORT).show()
            } else {
                defaultSmsLauncher.launch(intent)
            }
        }

        btnSaveCloudConfigStep.setOnClickListener {
            saveCloudConfigFromStep(allowBlank = false, showToast = true)
        }

        btnOpenSimBindingStep.setOnClickListener {
            startActivity(Intent(this, SimBindingActivity::class.java))
        }

        btnOnboardingSecondary.setOnClickListener {
            if (currentStep < TOTAL_STEPS - 1) {
                currentStep += 1
                refreshStatuses()
                updateStepUi()
                syncAntiFraudCountdown()
            }
        }

        btnOnboardingPrimary.setOnClickListener {
            if (currentStep == 0 && !OnboardingStateStore.isAntiFraudAcknowledged(this)) {
                Toast.makeText(this, "请先完成安全确认", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentStep == 3) {
                val saved = saveCloudConfigFromStep(allowBlank = true, showToast = false)
                if (!saved) {
                    return@setOnClickListener
                }
            }

            if (currentStep >= TOTAL_STEPS - 1) {
                exitOnboarding()
                return@setOnClickListener
            }

            currentStep += 1
            refreshStatuses()
            updateStepUi()
            syncAntiFraudCountdown()
        }
    }

    private fun seedCloudInputs() {
        val config = CloudSettingsManager.getConfig(this)
        etOnboardingBroker.setText(config.broker)
        etOnboardingTopic.setText(config.topic)
        etOnboardingPassword.setText(config.password)
        updateModeSelection(UsageModeManager.getMode(this), persist = false)
    }

    private fun refreshStatuses() {
        refreshAntiFraudStep()
        refreshPermissionStep()
        refreshDefaultSmsStep()
        refreshCloudStep()
        refreshSimBindingStep()
        refreshFinishStep()
    }

    private fun refreshAntiFraudStep() {
        val acknowledged = OnboardingStateStore.isAntiFraudAcknowledged(this)
        val countdownActive = hasAntiFraudCountdownActive()
        val remainingSeconds = getAntiFraudCountdownSecondsRemaining()
        tvAntiFraudStatus.text = if (acknowledged) {
            "高风险提醒已确认。\n仍请记住：不要把房间号（MQTT Topic）、加密密码、验证码和短信内容告诉任何人。"
        } else {
            "高风险提醒：\n1. 别人要求你安装 dSIM，极可能是诈骗。\n2. 不要把房间号（MQTT Topic）、加密密码、验证码、短信内容告诉任何人。\n3. 远程协助配置、代收验证码、让你把主力机接入云端，都属于高风险行为。\n4. 涉及支付、实名、金融、社交账号的设备，尤其不要交给别人指导配置。"
        }
        tvAntiFraudStatus.setTextColor(
            Color.parseColor(if (acknowledged) "#1F6B4F" else "#8A1F2D")
        )
        btnAcknowledgeAntiFraud.text = when {
            acknowledged -> "风险确认已完成"
            countdownActive -> "请先阅读 ${remainingSeconds} 秒"
            else -> "我已了解风险，继续使用"
        }
        btnAcknowledgeAntiFraud.isEnabled = !acknowledged && !countdownActive
        btnAcknowledgeAntiFraud.alpha = if (btnAcknowledgeAntiFraud.isEnabled) 1f else 0.72f
        updateModeCardEnabled(cardModeBidirectional, acknowledged)
        updateModeCardEnabled(cardModeReceiveOnly, acknowledged)
        updateModeCardEnabled(cardModeForwardOnly, acknowledged)
        updateModeCardEnabled(cardModeLocalOnly, acknowledged)
        updateModeSelection(UsageModeManager.getMode(this), persist = false)
    }

    private fun refreshPermissionStep() {
        val missing = CorePermissionHelper.missingPermissionLabels(this)
        tvPermissionStatus.text = if (missing.isEmpty()) {
            "基础权限已完成。${CorePermissionHelper.grantedSummary(this)}。"
        } else {
            "基础权限还没配齐。${CorePermissionHelper.grantedSummary(this)}。"
        }
        tvPermissionDetail.text = if (missing.isEmpty()) {
            "短信读取、短信接收、发送短信、电话状态、通知这些基础入口已经就绪。"
        } else {
            "还缺：${missing.joinToString("、")}。你可以现在授权，也可以先跳过，首页会继续提醒你补完。"
        }
        btnRequestCorePermissions.text = if (missing.isEmpty()) "基础权限已授权" else "授权基础权限"
        btnRequestCorePermissions.alpha = if (missing.isEmpty()) 0.82f else 1f
    }

    private fun refreshDefaultSmsStep() {
        val isDefault = DefaultSmsManager.isDefaultSmsApp(this)
        tvDefaultSmsStepStatus.text = if (isDefault) {
            "默认短信应用：已完成。系统短信会话、系统短信库和 dSIM 会更一致。"
        } else {
            "默认短信应用：未完成。建议尽快设为默认短信应用，避免系统短信和 dSIM 行为不一致。"
        }
        btnRequestDefaultSmsStep.text = if (isDefault) "当前已是默认短信应用" else "设为默认短信应用"
        btnRequestDefaultSmsStep.alpha = if (isDefault) 0.82f else 1f
    }

    private fun refreshCloudStep() {
        val mode = UsageModeManager.getMode(this)
        val hasConfig = CloudSettingsManager.hasConnectionConfig(this)
        val isConnected = MqttSyncService.isConnected()
        tvCloudStepHint.text = if (mode == UsageMode.LOCAL_ONLY) {
            "当前是本地模式。你可以先保存备用的 MQTT 服务器、房间号（MQTT Topic）和加密密码，但这一步不会启用云端连接。"
        } else {
            "保存后会立即连接云端，并默认开启自动连接和断线重连。房间号和加密密码都不要告诉别人。"
        }
        btnSaveCloudConfigStep.text = if (mode == UsageMode.LOCAL_ONLY) "保存备用配置" else "保存并连接"
        tvCloudConfigStatus.text = when {
            mode == UsageMode.LOCAL_ONLY && hasConfig ->
                "备用配置已保存。切回非本地模式后，能直接用这套配置连接。"
            mode == UsageMode.LOCAL_ONLY ->
                "当前模式不会连接云端，这一步可以先跳过。"
            isConnected ->
                "云端已连接。你可以继续下一步。"
            hasConfig ->
                "云端配置已保存，点击按钮会立即连接。"
            else ->
                "还没有保存 MQTT 服务器、房间号（MQTT Topic）和加密密码。"
        }
    }

    private fun refreshSimBindingStep() {
        lifecycleScope.launch {
            val hasBinding = withContext(Dispatchers.IO) {
                SetupChecklistManager.hasLocalSimBinding(this@OnboardingActivity)
            }
            tvSimBindingStepStatus.text = if (hasBinding) {
                "本机 SIM 绑定：已完成。当前手机已经有可用的本机发短信号码。"
            } else {
                "本机 SIM 绑定：未完成。建议至少先绑定一张本机卡，后面发短信和来源识别会更稳定。"
            }
            btnOpenSimBindingStep.text = if (hasBinding) "继续查看本机 SIM" else "去绑定本机 SIM"
            btnOpenSimBindingStep.alpha = if (hasBinding) 0.92f else 1f
        }
    }

    private fun refreshFinishStep() {
        lifecycleScope.launch {
            val missingItems = withContext(Dispatchers.IO) {
                SetupChecklistManager.missingItems(this@OnboardingActivity)
            }
            val mode = UsageModeManager.getMode(this@OnboardingActivity)
            val lines = mutableListOf<String>()
            lines += "使用模式：${UsageModeManager.displayName(mode)}"
            lines += UsageModeManager.description(mode)
            lines += ""
            lines += "安全确认：${if (OnboardingStateStore.isAntiFraudAcknowledged(this@OnboardingActivity)) "已完成" else "未完成"}"
            lines += "基础权限：${if (CorePermissionHelper.hasAllPermissions(this@OnboardingActivity)) "已完成" else "待授权"}"
            lines += "默认短信：${if (DefaultSmsManager.isDefaultSmsApp(this@OnboardingActivity)) "已完成" else "待设置"}"
            lines += if (mode == UsageMode.LOCAL_ONLY) {
                "云端配置：本地模式暂不需要"
            } else if (CloudSettingsManager.hasConnectionConfig(this@OnboardingActivity)) {
                "云端配置：已填写"
            } else {
                "云端配置：待填写"
            }
            val hasBinding = withContext(Dispatchers.IO) {
                SetupChecklistManager.hasLocalSimBinding(this@OnboardingActivity)
            }
            lines += "SIM 绑定：${if (hasBinding) "已完成" else "待绑定"}"
            lines += ""
            lines += if (missingItems.isEmpty()) {
                "当前关键项都齐了，可以直接进入 dSIM。"
            } else {
                "还缺：${missingItems.joinToString("、")}。你可以先进入 dSIM，首页会继续提醒你补完。"
            }
            tvFinishSummary.text = lines.joinToString("\n")
        }
    }

    private fun updateModeSelection(mode: UsageMode, persist: Boolean = true) {
        if (persist) {
            UsageModeManager.setMode(this, mode)
        }
        tvOnboardingModeBadge.text = UsageModeManager.displayName(mode)
        val accent = modeAccent(mode)
        tvOnboardingModeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(accent.soft))
        tvOnboardingModeBadge.setTextColor(Color.parseColor(accent.text))

        styleModeCard(cardModeBidirectional, UsageMode.BIDIRECTIONAL_SYNC, mode == UsageMode.BIDIRECTIONAL_SYNC)
        styleModeCard(cardModeReceiveOnly, UsageMode.RECEIVE_ONLY, mode == UsageMode.RECEIVE_ONLY)
        styleModeCard(cardModeForwardOnly, UsageMode.FORWARD_ONLY, mode == UsageMode.FORWARD_ONLY)
        styleModeCard(cardModeLocalOnly, UsageMode.LOCAL_ONLY, mode == UsageMode.LOCAL_ONLY)
        refreshCloudStep()
        refreshFinishStep()
    }

    private fun styleModeCard(card: MaterialCardView, mode: UsageMode, selected: Boolean) {
        val accent = modeAccent(mode)
        card.strokeWidth = dp(if (selected) 2 else 1)
        card.strokeColor = Color.parseColor(if (selected) accent.border else "#DCE5EF")
        card.setCardBackgroundColor(
            Color.parseColor(if (selected) accent.soft else "#F8FAFC")
        )
    }

    private fun updateModeCardEnabled(card: MaterialCardView, enabled: Boolean) {
        card.isEnabled = enabled
        card.isClickable = enabled
        card.alpha = if (enabled) 1f else 0.58f
    }

    private fun saveCloudConfigFromStep(allowBlank: Boolean, showToast: Boolean): Boolean {
        val rawBroker = etOnboardingBroker.text.toString().trim()
        val broker = rawBroker
            .ifBlank { CloudSettingsManager.DEFAULT_BROKER }
        val topic = etOnboardingTopic.text.toString().trim()
        val password = etOnboardingPassword.text.toString().trim()
        val hasAnyInput = rawBroker.isNotBlank() || topic.isNotBlank() || password.isNotBlank()

        if (!hasAnyInput && allowBlank) {
            return true
        }
        if (topic.isBlank()) {
            etOnboardingTopic.error = "请输入房间号（MQTT Topic）"
            return false
        }
        if (password.isBlank()) {
            etOnboardingPassword.error = "请输入加密密码"
            return false
        }

        CloudSettingsManager.saveConfig(this, broker, topic, password)

        if (UsageModeManager.canUseCloud(this)) {
            CloudSettingsManager.setAutoConnectEnabled(this, true)
            CloudSettingsManager.setAutoReconnectEnabled(this, true)
            ContextCompat.startForegroundService(
                this,
                Intent(this, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_CONNECT
                    putExtra("MQTT_BROKER", broker)
                    putExtra("MQTT_TOPIC", topic)
                    putExtra("MQTT_PASSWORD", password)
                }
            )
        }

        refreshStatuses()
        if (showToast) {
            val message = if (UsageModeManager.isLocalOnly(this)) {
                "备用云端配置已保存"
            } else {
                "云端配置已保存，正在连接"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun updateStepUi() {
        val antiFraudAcknowledged = OnboardingStateStore.isAntiFraudAcknowledged(this)
        stepViews.forEachIndexed { index, view ->
            view.visibility = if (index == currentStep) View.VISIBLE else View.GONE
        }

        tvOnboardingStep.text = "步骤 ${currentStep + 1} / $TOTAL_STEPS"
        when (currentStep) {
            0 -> {
                tvOnboardingTitle.text = "高风险提醒，请先完整阅读"
                tvOnboardingBody.text = "dSIM 会接触短信、验证码和云端同步能力。请先确认你是在为自己配置，而不是在替别人代收短信、交出验证码或开放主力机。"
                btnOnboardingSecondary.visibility = View.GONE
                btnOnboardingPrimary.text = if (antiFraudAcknowledged) "继续" else "请先完成安全确认"
                btnOnboardingPrimary.isEnabled = antiFraudAcknowledged
                btnOnboardingPrimary.alpha = if (antiFraudAcknowledged) 1f else 0.55f
            }

            1 -> {
                tvOnboardingTitle.text = "授权基础权限"
                tvOnboardingBody.text = "权限先给齐，短信读取、发送和通知这些基础动作才不会半通半断。"
                btnOnboardingSecondary.visibility = View.VISIBLE
                btnOnboardingSecondary.text = "跳过这步"
                btnOnboardingPrimary.text = "下一步"
                btnOnboardingPrimary.isEnabled = true
                btnOnboardingPrimary.alpha = 1f
            }

            2 -> {
                tvOnboardingTitle.text = "设为默认短信应用"
                tvOnboardingBody.text = "不是强制，但建议尽快完成。这样系统短信库、会话和通知会更一致。"
                btnOnboardingSecondary.visibility = View.VISIBLE
                btnOnboardingSecondary.text = "跳过这步"
                btnOnboardingPrimary.text = "下一步"
                btnOnboardingPrimary.isEnabled = true
                btnOnboardingPrimary.alpha = 1f
            }

            3 -> {
                tvOnboardingTitle.text = "填写云端配置"
                tvOnboardingBody.text = "MQTT 服务器、房间号（MQTT Topic）和加密密码都在这里。非本地模式保存后会立即连接。"
                btnOnboardingSecondary.visibility = View.VISIBLE
                btnOnboardingSecondary.text = "跳过这步"
                btnOnboardingPrimary.text = "下一步"
                btnOnboardingPrimary.isEnabled = true
                btnOnboardingPrimary.alpha = 1f
            }

            4 -> {
                tvOnboardingTitle.text = "绑定本机 SIM"
                tvOnboardingBody.text = "建议至少完成一张本机卡绑定，来源识别、发短信和卡槽判断会更稳定。"
                btnOnboardingSecondary.visibility = View.VISIBLE
                btnOnboardingSecondary.text = "跳过这步"
                btnOnboardingPrimary.text = "下一步"
                btnOnboardingPrimary.isEnabled = true
                btnOnboardingPrimary.alpha = 1f
            }

            else -> {
                tvOnboardingTitle.text = "准备好了"
                tvOnboardingBody.text = "这里是当前配置汇总。就算还没全部做完，也可以先进入 dSIM，后面继续补。"
                btnOnboardingSecondary.visibility = View.GONE
                btnOnboardingPrimary.text = "进入 dSIM"
                btnOnboardingPrimary.isEnabled = true
                btnOnboardingPrimary.alpha = 1f
            }
        }
    }

    private fun exitOnboarding() {
        stopAntiFraudCountdown()
        if (!OnboardingStateStore.isAntiFraudAcknowledged(this)) {
            finish()
            return
        }
        OnboardingStateStore.markSeen(this)
        UsageModeManager.applyMode(this, UsageModeManager.getMode(this))
        if (returnHomeOnFinish) {
            startActivity(
                Intent(this, SmsListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
        finish()
    }

    private fun modeAccent(mode: UsageMode): AccentStyle {
        return when (mode) {
            UsageMode.BIDIRECTIONAL_SYNC -> AccentStyle(
                soft = "#F1EBFF",
                border = "#6A43D8",
                text = "#6A43D8"
            )

            UsageMode.RECEIVE_ONLY -> AccentStyle(
                soft = "#EAF2FF",
                border = "#2457F5",
                text = "#2457F5"
            )

            UsageMode.FORWARD_ONLY -> AccentStyle(
                soft = "#EAF7F0",
                border = "#1F8A4D",
                text = "#1F8A4D"
            )

            UsageMode.LOCAL_ONLY -> AccentStyle(
                soft = "#EEF1F5",
                border = "#123B48",
                text = "#123B48"
            )
        }
    }

    private fun handleModeSelection(mode: UsageMode) {
        if (!isAntiFraudSelectionUnlocked()) {
            return
        }
        updateModeSelection(mode)
    }

    private fun isAntiFraudSelectionUnlocked(): Boolean {
        return OnboardingStateStore.isAntiFraudAcknowledged(this)
    }

    private fun hasAntiFraudCountdownActive(): Boolean {
        return !isAntiFraudSelectionUnlocked() && getAntiFraudCountdownSecondsRemaining() > 0
    }

    private fun getAntiFraudCountdownSecondsRemaining(): Int {
        if (antiFraudCountdownDeadlineMs <= 0L) {
            return 0
        }
        val remainingMs = antiFraudCountdownDeadlineMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) {
            return 0
        }
        return ((remainingMs + 999L) / 1000L).toInt()
    }

    private fun syncAntiFraudCountdown() {
        stopAntiFraudCountdown()
        if (currentStep != 0 || isAntiFraudSelectionUnlocked()) {
            return
        }
        if (antiFraudCountdownDeadlineMs <= 0L) {
            antiFraudCountdownDeadlineMs = SystemClock.elapsedRealtime() + ANTI_FRAUD_COUNTDOWN_MS
        }
        antiFraudCountdownJob = lifecycleScope.launch {
            while (true) {
                refreshStatuses()
                updateStepUi()
                if (!hasAntiFraudCountdownActive()) {
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun stopAntiFraudCountdown() {
        antiFraudCountdownJob?.cancel()
        antiFraudCountdownJob = null
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private data class AccentStyle(
        val soft: String,
        val border: String,
        val text: String
    )
}
