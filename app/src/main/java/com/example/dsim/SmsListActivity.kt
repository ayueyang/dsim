package com.example.dsim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var profileEditorState: ProfileEditorState? = null

    private val avatarPresets = listOf(
        AvatarPreset("man", "男士", R.drawable.avatar_preset_man),
        AvatarPreset("woman", "女士", R.drawable.avatar_preset_woman),
        AvatarPreset("boy", "男孩", R.drawable.avatar_preset_boy),
        AvatarPreset("girl", "女孩", R.drawable.avatar_preset_girl),
        AvatarPreset("elder_man", "长者", R.drawable.avatar_preset_elder_man),
        AvatarPreset("elder_woman", "奶奶", R.drawable.avatar_preset_elder_woman)
    )

    private data class AvatarPreset(
        val id: String,
        val label: String,
        val resId: Int
    )

    private data class ProfileEditorState(
        val address: String,
        val dialog: BottomSheetDialog,
        val remarkInput: EditText,
        val avatarTextInput: EditText,
        val previewCard: MaterialCardView,
        val previewImage: ImageView,
        val previewText: TextView,
        val presetAdapter: AvatarPresetAdapter,
        var avatarMode: String,
        var avatarPreset: String,
        var avatarImageUri: String
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startCloudDaemon()
    }

    private val avatarImagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        profileEditorState?.let { state ->
            state.avatarMode = ConversationProfileStore.AVATAR_MODE_IMAGE
            state.avatarImageUri = uri.toString()
            state.avatarPreset = ""
            state.presetAdapter.updateSelection(null)
            updateProfileAvatarPreview(state)
        }
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

        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_conversation_profile, null)
        val numberView = content.findViewById<TextView>(R.id.tvProfileNumber)
        val remarkInput = content.findViewById<EditText>(R.id.etProfileRemark)
        val avatarTextInput = content.findViewById<EditText>(R.id.etProfileAvatarText)
        val previewCard = content.findViewById<MaterialCardView>(R.id.cardProfileAvatarPreview)
        val previewImage = content.findViewById<ImageView>(R.id.ivProfileAvatarPreview)
        val previewText = content.findViewById<TextView>(R.id.tvProfileAvatarPreview)
        val presetList = content.findViewById<RecyclerView>(R.id.rvAvatarPresets)
        val pickImage = content.findViewById<TextView>(R.id.tvPickProfileImage)
        val useTextAvatar = content.findViewById<TextView>(R.id.tvUseProfileTextAvatar)
        val clearProfile = content.findViewById<TextView>(R.id.tvClearProfile)
        val cancelProfile = content.findViewById<TextView>(R.id.tvCancelProfile)
        val saveProfile = content.findViewById<TextView>(R.id.tvSaveProfile)

        numberView.text = "当前号码：$displayNumber"
        remarkInput.setText(profile.remark)
        remarkInput.setSelection(remarkInput.text.length)
        avatarTextInput.setText(profile.avatarText)
        avatarTextInput.setSelection(avatarTextInput.text.length)

        val presetAdapter = AvatarPresetAdapter(
            avatarPresets,
            selectedId = profile.avatarPreset.takeIf {
                profile.avatarMode == ConversationProfileStore.AVATAR_MODE_PRESET
            }
        ) { preset ->
            profileEditorState?.let { state ->
                state.avatarMode = ConversationProfileStore.AVATAR_MODE_PRESET
                state.avatarPreset = preset.id
                state.avatarImageUri = ""
                state.presetAdapter.updateSelection(preset.id)
                updateProfileAvatarPreview(state)
            }
        }
        presetList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        presetList.adapter = presetAdapter

        val state = ProfileEditorState(
            address = address,
            dialog = dialog,
            remarkInput = remarkInput,
            avatarTextInput = avatarTextInput,
            previewCard = previewCard,
            previewImage = previewImage,
            previewText = previewText,
            presetAdapter = presetAdapter,
            avatarMode = profile.avatarMode,
            avatarPreset = profile.avatarPreset,
            avatarImageUri = profile.avatarImageUri
        )
        profileEditorState = state
        updateProfileAvatarPreview(state)

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (state.avatarMode == ConversationProfileStore.AVATAR_MODE_TEXT) {
                    updateProfileAvatarPreview(state)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        }
        remarkInput.addTextChangedListener(textWatcher)
        avatarTextInput.addTextChangedListener(textWatcher)

        pickImage.setOnClickListener {
            avatarImagePicker.launch(arrayOf("image/*"))
        }
        useTextAvatar.setOnClickListener {
            state.avatarMode = ConversationProfileStore.AVATAR_MODE_TEXT
            state.avatarPreset = ""
            state.avatarImageUri = ""
            state.presetAdapter.updateSelection(null)
            updateProfileAvatarPreview(state)
        }
        clearProfile.setOnClickListener {
            ConversationProfileStore.clear(this, address)
            refreshConversationItems()
            Toast.makeText(this, "已清除会话备注", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        cancelProfile.setOnClickListener { dialog.dismiss() }
        saveProfile.setOnClickListener {
            val remark = remarkInput.text.toString().trim()
            val avatarText = avatarTextInput.text.toString().trim().take(2)
            ConversationProfileStore.save(
                context = this,
                address = address,
                remark = remark,
                avatarText = avatarText,
                avatarMode = state.avatarMode,
                avatarPreset = state.avatarPreset,
                avatarImageUri = state.avatarImageUri
            )
            refreshConversationItems()
            Toast.makeText(this, "会话资料已保存", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener {
            if (profileEditorState?.dialog == dialog) {
                profileEditorState = null
            }
        }
        dialog.show()

        return true
    }

    private fun updateProfileAvatarPreview(state: ProfileEditorState) {
        val remark = state.remarkInput.text.toString().trim()
        val avatarText = state.avatarTextInput.text.toString().trim().take(2)

        when (state.avatarMode) {
            ConversationProfileStore.AVATAR_MODE_IMAGE -> {
                val uri = state.avatarImageUri.takeIf { it.isNotBlank() }
                if (uri == null || !setImageUriSafely(state.previewImage, uri)) {
                    state.avatarMode = ConversationProfileStore.AVATAR_MODE_TEXT
                    state.avatarImageUri = ""
                    updateProfileAvatarPreview(state)
                    return
                }
                state.previewCard.setCardBackgroundColor(Color.WHITE)
                state.previewImage.visibility = View.VISIBLE
                state.previewText.visibility = View.GONE
            }

            ConversationProfileStore.AVATAR_MODE_PRESET -> {
                val preset = findAvatarPreset(state.avatarPreset)
                if (preset == null) {
                    state.avatarMode = ConversationProfileStore.AVATAR_MODE_TEXT
                    state.avatarPreset = ""
                    updateProfileAvatarPreview(state)
                    return
                }
                state.previewCard.setCardBackgroundColor(Color.WHITE)
                state.previewImage.setImageResource(preset.resId)
                state.previewImage.visibility = View.VISIBLE
                state.previewText.visibility = View.GONE
            }

            else -> {
                state.previewImage.setImageDrawable(null)
                state.previewImage.visibility = View.GONE
                state.previewText.visibility = View.VISIBLE
                state.previewText.text = resolveAvatarText(state.address, remark, avatarText)
                val (background, foreground) = resolveAvatarPalette(remark.ifBlank { state.address })
                state.previewCard.setCardBackgroundColor(Color.parseColor(background))
                state.previewText.setTextColor(Color.parseColor(foreground))
            }
        }
    }

    private fun resolveAvatarText(address: String, remark: String, avatarText: String): String {
        val customAvatar = avatarText.trim().take(2)
        if (customAvatar.isNotBlank()) {
            return customAvatar
        }
        val cleanRemark = remark.trim()
        if (cleanRemark.isNotBlank()) {
            return cleanRemark.take(1).uppercase()
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

    private fun resolveAvatarPalette(seed: String): Pair<String, String> {
        val palette = listOf(
            "#E8F4FF" to "#1677C8",
            "#EAF8F0" to "#218A52",
            "#F2EDFF" to "#6750A4",
            "#FFF0E6" to "#B45309",
            "#EAF7F7" to "#0F766E"
        )
        return palette[Math.floorMod(seed.hashCode(), palette.size)]
    }

    private fun findAvatarPreset(id: String): AvatarPreset? {
        return avatarPresets.firstOrNull { it.id == id }
    }

    private fun setImageUriSafely(imageView: ImageView, uriString: String): Boolean {
        return try {
            imageView.setImageURI(Uri.parse(uriString))
            true
        } catch (_: Exception) {
            false
        }
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
            val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
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
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(sms.timestamp))
            bindConversationAvatar(holder, sms.address, profile)
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
            holder.ivAvatar.setImageDrawable(null)
            holder.ivAvatar.visibility = View.GONE
            holder.tvAvatar.visibility = View.VISIBLE
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

        private fun bindConversationAvatar(
            holder: ViewHolder,
            address: String,
            profile: ConversationProfile
        ) {
            holder.ivAvatar.setImageDrawable(null)
            when (profile.avatarMode) {
                ConversationProfileStore.AVATAR_MODE_IMAGE -> {
                    if (profile.avatarImageUri.isNotBlank() &&
                        setImageUriSafely(holder.ivAvatar, profile.avatarImageUri)
                    ) {
                        holder.cardAvatar.setCardBackgroundColor(Color.WHITE)
                        holder.ivAvatar.visibility = View.VISIBLE
                        holder.tvAvatar.visibility = View.GONE
                        return
                    }
                }

                ConversationProfileStore.AVATAR_MODE_PRESET -> {
                    findAvatarPreset(profile.avatarPreset)?.let { preset ->
                        holder.cardAvatar.setCardBackgroundColor(Color.WHITE)
                        holder.ivAvatar.setImageResource(preset.resId)
                        holder.ivAvatar.visibility = View.VISIBLE
                        holder.tvAvatar.visibility = View.GONE
                        return
                    }
                }
            }

            holder.ivAvatar.visibility = View.GONE
            holder.tvAvatar.visibility = View.VISIBLE
            holder.tvAvatar.text = resolveAvatarText(address, profile.remark, profile.avatarText)
            val (background, foreground) = resolveAvatarPalette(profile.remark.ifBlank { address })
            holder.cardAvatar.setCardBackgroundColor(Color.parseColor(background))
            holder.tvAvatar.setTextColor(Color.parseColor(foreground))
        }

        override fun getItemCount() = list.size
    }

    private inner class AvatarPresetAdapter(
        private val presets: List<AvatarPreset>,
        selectedId: String?,
        private val onSelect: (AvatarPreset) -> Unit
    ) : RecyclerView.Adapter<AvatarPresetAdapter.ViewHolder>() {

        private var currentSelectedId: String? = selectedId

        fun updateSelection(selectedId: String?) {
            currentSelectedId = selectedId
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardAvatarPreset)
            val image: ImageView = view.findViewById(R.id.ivAvatarPreset)
            val label: TextView = view.findViewById(R.id.tvAvatarPresetLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_avatar_preset, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val preset = presets[position]
            val isSelected = preset.id == currentSelectedId
            val density = holder.itemView.resources.displayMetrics.density
            holder.image.setImageResource(preset.resId)
            holder.label.text = preset.label
            holder.card.strokeColor = Color.parseColor(if (isSelected) "#123B48" else "#E5EAF1")
            holder.card.strokeWidth = (density * if (isSelected) 2f else 1f).toInt()
            holder.card.setCardBackgroundColor(Color.WHITE)
            holder.itemView.setOnClickListener { onSelect(preset) }
            holder.card.setOnClickListener { onSelect(preset) }
        }

        override fun getItemCount(): Int = presets.size
    }
}
