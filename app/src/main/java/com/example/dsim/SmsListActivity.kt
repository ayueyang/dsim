package com.example.dsim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private companion object {
        const val OTP_CONVERSATION_PIN_KEY = "__DSIM_OTP_CONVERSATION__"
        const val MAX_PIN_PRIORITY = 99
    }

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

    private data class ContactPhoneOption(
        val contactId: Long,
        val displayName: String,
        val phoneNumber: String,
        val searchText: String
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

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showContactPickerDialog()
        } else {
            Toast.makeText(this, "未获得联系人权限，无法从联系人新建会话", Toast.LENGTH_SHORT).show()
        }
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

        return items.sortedWith(
            compareBy<ConversationListItem> {
                ConversationProfileStore.getPinPriority(this, it.pinKey) ?: Int.MAX_VALUE
            }.thenByDescending { it.sortTimestamp }
        )
    }

    private fun showCreateConversationDialog() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_create_conversation, null)
        val input = content.findViewById<EditText>(R.id.etCreateConversationPhone)
        val contactButton = content.findViewById<TextView>(R.id.tvPickContactForConversation)
        val cancelButton = content.findViewById<TextView>(R.id.tvCancelCreateConversation)
        val openButton = content.findViewById<TextView>(R.id.tvOpenCreateConversation)

        contactButton.setOnClickListener {
            dialog.dismiss()
            openContactPickerWithPermission()
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        openButton.setOnClickListener {
            val rawAddress = input.text.toString().trim()
            if (rawAddress.isBlank()) {
                input.error = "请输入手机号"
                return@setOnClickListener
            }

            val normalizedAddress = GlobalNumberUtils.formatToE164(this, rawAddress)
            openChat(normalizedAddress.ifBlank { rawAddress })
            dialog.dismiss()
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun openContactPickerWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showContactPickerDialog()
            return
        }

        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun showContactPickerDialog() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_contact_picker, null)
        val searchInput = content.findViewById<EditText>(R.id.etContactSearch)
        val emptyView = content.findViewById<TextView>(R.id.tvContactPickerEmpty)
        val recyclerView = content.findViewById<RecyclerView>(R.id.rvContactOptions)
        val adapter = ContactPhoneAdapter(emptyList()) { option ->
            handleContactSelected(option, dialog)
        }
        var allContactOptions: List<ContactPhoneOption> = emptyList()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fun render(query: String = searchInput.text.toString()) {
            val filtered = filterContactOptions(allContactOptions, query)
            adapter.updateData(filtered)
            recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
            emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            emptyView.text = when {
                allContactOptions.isEmpty() -> "没有可用联系人号码"
                query.isNotBlank() -> "没有匹配的联系人"
                else -> "没有可用联系人号码"
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()

        lifecycleScope.launch {
            allContactOptions = withContext(Dispatchers.IO) {
                loadContactPhoneOptions()
            }
            if (dialog.isShowing) {
                render()
            }
        }
    }

    private fun filterContactOptions(
        options: List<ContactPhoneOption>,
        query: String
    ): List<ContactPhoneOption> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank()) {
            return options
        }
        val queryDigits = cleanQuery.filter { it.isDigit() }
        return options.filter { option ->
            option.searchText.contains(cleanQuery) ||
                (queryDigits.isNotBlank() && option.phoneNumber.filter { it.isDigit() }.contains(queryDigits))
        }
    }

    private fun loadContactPhoneOptions(): List<ContactPhoneOption> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val dedupeKeys = linkedSetOf<String>()
        val results = mutableListOf<ContactPhoneOption>()

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val rawNumber = cursor.getString(numberIndex).orEmpty().trim()
                if (rawNumber.isBlank()) {
                    continue
                }
                val name = cursor.getString(nameIndex).orEmpty().trim().ifBlank { rawNumber }
                val digits = rawNumber.filter { it.isDigit() }
                val dedupeKey = "${name.lowercase(Locale.ROOT)}|$digits"
                if (!dedupeKeys.add(dedupeKey)) {
                    continue
                }
                results += ContactPhoneOption(
                    contactId = cursor.getLong(contactIdIndex),
                    displayName = name,
                    phoneNumber = rawNumber,
                    searchText = "$name $rawNumber $digits".lowercase(Locale.ROOT)
                )
            }
        }

        return results.sortedWith(
            compareBy<ContactPhoneOption> { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.phoneNumber.filter { char -> char.isDigit() } }
        )
    }

    private fun handleContactSelected(
        option: ContactPhoneOption,
        dialog: BottomSheetDialog
    ) {
        val normalizedAddress = GlobalNumberUtils.formatToE164(this, option.phoneNumber)
        val address = normalizedAddress.ifBlank { option.phoneNumber.trim() }
        saveContactRemarkIfEmpty(address, option.displayName)
        dialog.dismiss()
        openChat(address)
    }

    private fun saveContactRemarkIfEmpty(address: String, contactName: String) {
        val cleanName = contactName.trim()
        if (cleanName.isBlank()) {
            return
        }
        val profile = ConversationProfileStore.load(this, address)
        if (profile.remark.isNotBlank()) {
            return
        }
        ConversationProfileStore.save(
            context = this,
            address = address,
            remark = cleanName,
            avatarText = profile.avatarText,
            avatarMode = profile.avatarMode,
            avatarPreset = profile.avatarPreset,
            avatarImageUri = profile.avatarImageUri
        )
        refreshConversationItems()
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

    private fun showConversationActionsDialog(
        pinKey: String,
        displayTitle: String,
        allowEditProfile: Boolean
    ): Boolean {
        val currentPriority = ConversationProfileStore.getPinPriority(this, pinKey)
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_conversation_actions, null)
        val titleView = content.findViewById<TextView>(R.id.tvConversationActionTitle)
        val subtitleView = content.findViewById<TextView>(R.id.tvConversationActionSubtitle)
        val input = content.findViewById<EditText>(R.id.etPinPriority)
        val savePin = content.findViewById<TextView>(R.id.tvSavePinPriority)
        val clearPin = content.findViewById<TextView>(R.id.tvClearPinPriority)
        val editProfile = content.findViewById<TextView>(R.id.tvEditConversationProfile)
        val cancel = content.findViewById<TextView>(R.id.tvCancelConversationAction)

        titleView.text = displayTitle.ifBlank { "会话操作" }
        subtitleView.text = "设置置顶优先级，1 最高，数字越大越靠后。"
        input.setText(currentPriority?.toString().orEmpty())
        if (currentPriority != null) {
            input.setSelection(input.text.length)
        }

        savePin.setOnClickListener {
            val raw = input.text.toString().trim()
            if (raw.isBlank() || raw == "0") {
                ConversationProfileStore.clearPinPriority(this, pinKey)
                refreshConversationItems()
                Toast.makeText(this, "已取消置顶", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setOnClickListener
            }

            val priority = raw.toIntOrNull()
            if (priority == null || priority !in 1..MAX_PIN_PRIORITY) {
                input.error = "请输入 1-$MAX_PIN_PRIORITY 的数字，或留空取消置顶"
                return@setOnClickListener
            }

            ConversationProfileStore.setPinPriority(this, pinKey, priority)
            refreshConversationItems()
            Toast.makeText(this, "已设置置顶 $priority", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        clearPin.setOnClickListener {
            ConversationProfileStore.clearPinPriority(this, pinKey)
            refreshConversationItems()
            Toast.makeText(this, "已取消置顶", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        if (allowEditProfile) {
            editProfile.visibility = View.VISIBLE
            editProfile.setOnClickListener {
                dialog.dismiss()
                showConversationProfileDialog(pinKey)
            }
        } else {
            editProfile.visibility = View.GONE
        }
        cancel.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
        return true
    }

    private fun showConversationProfileDialog(address: String): Boolean {
        val profile = ConversationProfileStore.load(this, address)
        val displayNumber = PrivacyModeManager.displayConversationAddress(this, address).ifBlank { address }

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

    sealed class ConversationListItem(val sortTimestamp: Long, val pinKey: String) {
        class NormalConversation(val sms: SmsMessage) : ConversationListItem(sms.timestamp, sms.address)
        class OtpConversation(
            val latestOtp: OtpMessageItem,
            val count: Int
        ) : ConversationListItem(latestOtp.sms.timestamp, OTP_CONVERSATION_PIN_KEY)
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
            val tvPinBadge: TextView = view.findViewById(R.id.tvPinBadge)
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
            val context = holder.itemView.context
            val profile = ConversationProfileStore.load(holder.itemView.context, sms.address)
            val title = buildConversationTitle(sms.address, profile)
            holder.tvSender.text = title
            val snippet = sms.body.replace('\n', ' ').trim()
            holder.tvSnippet.text = PrivacyModeManager.displayMessageText(context, snippet)
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(sms.timestamp))
            bindConversationAvatar(holder, sms.address, profile)
            bindPinBadge(holder, item.pinKey)
            holder.cardConversation.setCardBackgroundColor(Color.WHITE)
            holder.cardConversation.strokeColor = Color.parseColor("#E7ECF2")

            holder.itemView.setOnClickListener {
                openChat(sms.address)
            }
            holder.itemView.setOnLongClickListener {
                showConversationActionsDialog(
                    pinKey = sms.address,
                    displayTitle = title,
                    allowEditProfile = true
                )
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
            bindPinBadge(holder, item.pinKey)
            holder.cardConversation.setCardBackgroundColor(Color.WHITE)
            holder.cardConversation.strokeColor = Color.parseColor("#F1E4C9")

            holder.itemView.setOnClickListener {
                openOtpConversation()
            }
            holder.itemView.setOnLongClickListener {
                showConversationActionsDialog(
                    pinKey = OTP_CONVERSATION_PIN_KEY,
                    displayTitle = "验证码",
                    allowEditProfile = false
                )
            }
        }

        private fun bindPinBadge(holder: ViewHolder, pinKey: String) {
            val priority = ConversationProfileStore.getPinPriority(holder.itemView.context, pinKey)
            if (priority == null) {
                holder.tvPinBadge.visibility = View.GONE
                return
            }
            holder.tvPinBadge.visibility = View.VISIBLE
            holder.tvPinBadge.text = "置顶 $priority"
        }

        private fun buildConversationTitle(
            address: String,
            profile: ConversationProfile
        ): String {
            val number = PrivacyModeManager.displayConversationAddress(this@SmsListActivity, address)
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

    private inner class ContactPhoneAdapter(
        private var options: List<ContactPhoneOption>,
        private val onSelect: (ContactPhoneOption) -> Unit
    ) : RecyclerView.Adapter<ContactPhoneAdapter.ViewHolder>() {

        fun updateData(newOptions: List<ContactPhoneOption>) {
            options = newOptions
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardContactOption)
            val avatarCard: MaterialCardView = view.findViewById(R.id.cardContactAvatar)
            val avatar: TextView = view.findViewById(R.id.tvContactAvatar)
            val name: TextView = view.findViewById(R.id.tvContactName)
            val phone: TextView = view.findViewById(R.id.tvContactPhone)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact_phone, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = options[position]
            val context = holder.itemView.context
            val displayPhone = PrivacyModeManager.displayPhone(context, option.phoneNumber)
                .ifBlank { option.phoneNumber }
            val avatarText = option.displayName.trim().take(1).uppercase(Locale.ROOT)
                .ifBlank { displayPhone.filter { it.isDigit() }.takeLast(2).ifBlank { "?" } }
            val (background, foreground) = resolveAvatarPalette(option.displayName)

            holder.avatar.text = avatarText
            holder.name.text = option.displayName
            holder.phone.text = displayPhone
            holder.avatarCard.setCardBackgroundColor(Color.parseColor(background))
            holder.avatar.setTextColor(Color.parseColor(foreground))
            holder.itemView.setOnClickListener { onSelect(option) }
            holder.card.setOnClickListener { onSelect(option) }
        }

        override fun getItemCount(): Int = options.size
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
