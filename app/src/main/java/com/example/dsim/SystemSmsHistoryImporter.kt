package com.example.dsim

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

object SystemSmsHistoryImporter {

    private const val PREFS_NAME = "dSIM_SYSTEM_HISTORY_IMPORT"
    private const val KEY_ENABLED = "ENABLED"
    private const val KEY_LAST_IMPORT_AT = "LAST_IMPORT_AT"
    private const val KEY_CURSOR_DATE = "CURSOR_DATE"
    private const val KEY_CURSOR_ID = "CURSOR_ID"
    private const val KEY_REACHED_END = "REACHED_END"
    private const val KEY_IMPORT_VERSION = "IMPORT_VERSION"

    private const val APP_DEDUPE_WINDOW_MS = 2 * 60 * 1000L
    private const val HISTORY_ACK_TIMEOUT_MS = 20_000L
    private const val CURRENT_IMPORT_VERSION = 2

    private val IMPORT_SMS_TYPES = arrayOf(
        Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
        Telephony.Sms.MESSAGE_TYPE_SENT.toString()
    )
    private const val IMPORT_SMS_SELECTION = "${Telephony.Sms.TYPE} IN (?, ?)"

    data class ImportResult(
        val scannedCount: Int,
        val importedCount: Int,
        val syncedCount: Int,
        val skippedCount: Int,
        val completedAll: Boolean,
        val wasPaused: Boolean,
        val stopReason: String?,
        val message: String
    )

    data class ImportProgress(
        val stage: String,
        val systemCount: Int,
        val appCount: Int,
        val scannedCount: Int,
        val importedCount: Int,
        val syncedCount: Int,
        val skippedCount: Int,
        val currentAddress: String,
        val cloudConnected: Boolean,
        val waitingForAck: Boolean,
        val startedAt: Long
    )

    interface ProgressListener {
        fun onProgress(progress: ImportProgress)
        fun isPauseRequested(): Boolean = false
    }

    data class LibraryCounts(
        val systemCount: Int,
        val appCount: Int
    )

    private data class CursorMarker(
        val timestamp: Long,
        val rowId: Long
    )

    private data class PublishAckResult(
        val success: Boolean,
        val detail: String? = null,
        val wasPaused: Boolean = false
    )

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun resetImportProgress(context: Context, clearLastImportAt: Boolean = false) {
        val editor = prefs(context)
            .edit()
            .remove(KEY_CURSOR_DATE)
            .remove(KEY_CURSOR_ID)
            .putBoolean(KEY_REACHED_END, false)

        if (clearLastImportAt) {
            editor.putLong(KEY_LAST_IMPORT_AT, 0L)
        }

        editor.apply()
    }

    suspend fun buildStatusText(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val lastImportAt = prefs.getLong(KEY_LAST_IMPORT_AT, 0L)
        val hasCursor = hasCursor(prefs)
        val reachedEnd = prefs.getBoolean(KEY_REACHED_END, false)
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val counts = loadLibraryCounts(context)

        val lines = mutableListOf<String>()
        lines += "系统库：${counts.systemCount} 条"
        lines += "软件库：${counts.appCount} 条"

        if (!enabled) {
            lines += "当前未开启。开启后才允许手动读取系统历史短信。"
            return@withContext lines.joinToString("\n")
        }

        lines += if (lastImportAt > 0L) {
            "上次执行：${formatter.format(Date(lastImportAt))}"
        } else {
            "尚未执行过历史短信导入"
        }

        val needsResetHint = counts.systemCount > 0 && counts.appCount == 0 && (hasCursor || reachedEnd)
        lines += when {
            needsResetHint -> "软件库目前是空的。如需重新从本机系统库导入，请先点“重置本机读取进度”，再点“开始导入历史短信”。"
            reachedEnd && lastImportAt > 0L -> "更早的系统短信已经扫完。"
            hasCursor -> "下次会从上次停下的位置继续处理更早的系统短信。"
            else -> "点击下方按钮后，会从较新的系统短信开始检查。"
        }

        lines += if (buildCloudPublisher(context) != null) {
            "云端已连接：会按队列逐条发送，收到对端成功确认后再继续下一条。"
        } else {
            "云端未连接：本次只会导入到本机 App 库。"
        }

        lines += "当前不再使用固定条数和冷却时间。"
        lines.joinToString("\n")
    }

    suspend fun importQueuedHistory(
        context: Context,
        progressListener: ProgressListener? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) {
            val counts = loadLibraryCounts(context)
            return@withContext ImportResult(
                scannedCount = 0,
                importedCount = 0,
                syncedCount = 0,
                skippedCount = 0,
                completedAll = false,
                wasPaused = false,
                stopReason = "请先在设置里开启“系统历史短信导入”。",
                message = buildResultMessage(
                    scannedCount = 0,
                    importedCount = 0,
                    syncedCount = 0,
                    skippedCount = 0,
                    completedAll = false,
                    stopReason = "请先在设置里开启“系统历史短信导入”。",
                    cloudPublisher = buildCloudPublisher(context),
                    counts = counts
                )
            )
        }

        val prefs = prefs(context)
        ensureImportStateUpToDate(prefs)
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val initialCounts = loadLibraryCounts(context)
        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        val localConfigs = dao.getActiveSimConfigs().filter { it.bindMode != "REMOTE_SHADOW" }
        val cloudPublisher = buildCloudPublisher(context)
        val hasCursor = hasCursor(prefs)
        val cursorDate = prefs.getLong(KEY_CURSOR_DATE, Long.MAX_VALUE)
        val cursorId = prefs.getLong(KEY_CURSOR_ID, Long.MAX_VALUE)
        val startedAt = System.currentTimeMillis()

        val selectionParts = mutableListOf(IMPORT_SMS_SELECTION)
        val selectionArgs = IMPORT_SMS_TYPES.toMutableList()
        if (hasCursor) {
            selectionParts += "(${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
            selectionArgs += cursorDate.toString()
            selectionArgs += cursorDate.toString()
            selectionArgs += cursorId.toString()
        }

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        var scannedCount = 0
        var importedCount = 0
        var syncedCount = 0
        var skippedCount = 0
        var completedAll = true
        var wasPaused = false
        var stopReason: String? = null
        var foundAnyRow = false

        fun dispatchProgress(stage: String, currentAddress: String = "", waitingForAck: Boolean = false) {
            progressListener?.onProgress(
                ImportProgress(
                    stage = stage,
                    systemCount = initialCounts.systemCount,
                    appCount = initialCounts.appCount + importedCount,
                    scannedCount = scannedCount,
                    importedCount = importedCount,
                    syncedCount = syncedCount,
                    skippedCount = skippedCount,
                    currentAddress = currentAddress,
                    cloudConnected = cloudPublisher != null,
                    waitingForAck = waitingForAck,
                    startedAt = startedAt
                )
            )
        }

        dispatchProgress(
            stage = if (cloudPublisher != null) "准备开始，导入后会等待对端确认" else "准备开始，只导入到本机软件库"
        )

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC"
        ) ?: run {
            val counts = loadLibraryCounts(context)
            return@withContext ImportResult(
                scannedCount = 0,
                importedCount = 0,
                syncedCount = 0,
                skippedCount = 0,
                completedAll = false,
                wasPaused = false,
                stopReason = "读取系统短信失败，请稍后再试。",
                message = buildResultMessage(
                    scannedCount = 0,
                    importedCount = 0,
                    syncedCount = 0,
                    skippedCount = 0,
                    completedAll = false,
                    stopReason = "读取系统短信失败，请稍后再试。",
                    cloudPublisher = cloudPublisher,
                    counts = counts
                )
            )
        }

        cursor.use { smsCursor ->
            val idIdx = smsCursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIdx = smsCursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = smsCursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = smsCursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = smsCursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val subIdIdx = smsCursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (smsCursor.moveToNext()) {
                if (progressListener?.isPauseRequested() == true) {
                    completedAll = false
                    wasPaused = true
                    stopReason = "已手动暂停，队列会从当前这条继续。"
                    break
                }

                foundAnyRow = true

                val marker = CursorMarker(
                    timestamp = smsCursor.getLong(dateIdx),
                    rowId = smsCursor.getLong(idIdx)
                )
                val rawAddress = smsCursor.getString(addressIdx).orEmpty()
                val body = smsCursor.getString(bodyIdx).orEmpty()
                val type = smsCursor.getInt(typeIdx)
                val subscriptionId = if (subIdIdx >= 0) smsCursor.getInt(subIdIdx) else -1
                val normalizedAddress = GlobalNumberUtils.formatToE164(context, rawAddress)
                    .ifBlank { rawAddress.ifBlank { "Unknown" } }
                val safeSubId = subscriptionId.takeIf { it > 0 }
                dispatchProgress(stage = "正在检查 $normalizedAddress", currentAddress = normalizedAddress)
                scannedCount++

                val source = SmsSourceResolver.resolveHistoryImportSource(
                    localConfigs = localConfigs,
                    deviceId = localDeviceId,
                    subscriptionId = safeSubId
                )

                val existingSms = dao.findSimilarLocalMessage(
                    deviceId = localDeviceId,
                    address = normalizedAddress,
                    body = body,
                    type = type,
                    mappingKey = source.mappingKey,
                    startTimestamp = marker.timestamp - APP_DEDUPE_WINDOW_MS,
                    endTimestamp = marker.timestamp + APP_DEDUPE_WINDOW_MS
                )
                var sms = existingSms

                if (sms == null) {
                    sms = SmsMessage(
                        uuid = UUID.randomUUID().toString(),
                        address = normalizedAddress,
                        body = body,
                        timestamp = marker.timestamp,
                        type = type,
                        status = 1,
                        deviceId = localDeviceId,
                        simId = source.simId,
                        iccid = null,
                        mappingKey = source.mappingKey
                    )
                    dao.insertMessage(sms)
                    importedCount++
                    dispatchProgress(stage = "已写入软件库", currentAddress = normalizedAddress)
                }

                if (cloudPublisher != null) {
                    dispatchProgress(
                        stage = "等待对端确认",
                        currentAddress = normalizedAddress,
                        waitingForAck = true
                    )
                    val publishResult = cloudPublisher.publishAndAwaitAck(
                        sms = sms,
                        remarkPhone = source.sourcePhoneNumber,
                        progressListener = progressListener
                    )
                    if (!publishResult.success) {
                        completedAll = false
                        wasPaused = publishResult.wasPaused
                        stopReason = publishResult.detail
                        break
                    }
                    syncedCount++
                    dispatchProgress(stage = "对端已确认", currentAddress = normalizedAddress)
                } else if (existingSms != null) {
                    skippedCount++
                    dispatchProgress(stage = "已跳过重复记录", currentAddress = normalizedAddress)
                }

                saveCursor(prefs, marker, reachedEnd = false)
            }
        }

        prefs.edit()
            .putLong(KEY_LAST_IMPORT_AT, System.currentTimeMillis())
            .putBoolean(KEY_REACHED_END, completedAll)
            .apply()

        if (!foundAnyRow && hasCursor) {
            completedAll = true
        }

        val counts = loadLibraryCounts(context)
        val message = buildResultMessage(
            scannedCount = scannedCount,
            importedCount = importedCount,
            syncedCount = syncedCount,
            skippedCount = skippedCount,
            completedAll = completedAll,
            stopReason = stopReason,
            cloudPublisher = cloudPublisher,
            counts = counts
        )

        ImportResult(
            scannedCount = scannedCount,
            importedCount = importedCount,
            syncedCount = syncedCount,
            skippedCount = skippedCount,
            completedAll = completedAll,
            wasPaused = wasPaused,
            stopReason = stopReason,
            message = message
        )
    }

    private suspend fun loadLibraryCounts(context: Context): LibraryCounts {
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        return LibraryCounts(
            systemCount = querySystemSmsCount(context),
            appCount = dao.countSmsMessages()
        )
    }

    private fun querySystemSmsCount(context: Context): Int {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                IMPORT_SMS_SELECTION,
                IMPORT_SMS_TYPES,
                null
            )?.use { it.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun ensureImportStateUpToDate(prefs: SharedPreferences) {
        val storedVersion = prefs.getInt(KEY_IMPORT_VERSION, 0)
        if (storedVersion >= CURRENT_IMPORT_VERSION) {
            return
        }

        prefs.edit()
            .remove(KEY_CURSOR_DATE)
            .remove(KEY_CURSOR_ID)
            .putBoolean(KEY_REACHED_END, false)
            .putInt(KEY_IMPORT_VERSION, CURRENT_IMPORT_VERSION)
            .apply()
    }

    private fun buildCloudPublisher(context: Context): CloudPublisher? {
        if (!MqttSyncService.isConnected()) {
            return null
        }

        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val topic = prefs.getString("TOPIC", "").orEmpty().trim()
        val password = prefs.getString("PASSWORD", "").orEmpty().trim()
        val client = MqttSyncService.globalMqttClient
        if (topic.isBlank() || password.isBlank() || client == null || !client.isConnected) {
            return null
        }

        return CloudPublisher(context, topic, password)
    }

    private fun buildResultMessage(
        scannedCount: Int,
        importedCount: Int,
        syncedCount: Int,
        skippedCount: Int,
        completedAll: Boolean,
        stopReason: String?,
        cloudPublisher: CloudPublisher?,
        counts: LibraryCounts
    ): String {
        val lines = mutableListOf<String>()
        lines += "本次扫描 $scannedCount 条，新增 $importedCount 条，云端确认 $syncedCount 条，跳过 $skippedCount 条。"
        lines += "系统库：${counts.systemCount} 条"
        lines += "软件库：${counts.appCount} 条"

        if (cloudPublisher == null) {
            lines += "当前未连接云端，本次只写入本机 App 库。"
        } else if (!stopReason.isNullOrBlank()) {
            lines += stopReason
        } else {
            lines += "云端已按顺序确认完成。"
        }

        lines += if (completedAll) {
            "更早的系统短信已经处理完了。"
        } else {
            "队列已暂停，下次会从当前这条继续重试。"
        }
        lines += "现在不再使用固定条数和冷却时间。"

        return lines.joinToString("\n")
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun hasCursor(prefs: SharedPreferences): Boolean {
        return prefs.contains(KEY_CURSOR_DATE) && prefs.contains(KEY_CURSOR_ID)
    }

    private fun saveCursor(
        prefs: SharedPreferences,
        marker: CursorMarker,
        reachedEnd: Boolean
    ) {
        prefs.edit()
            .putLong(KEY_CURSOR_DATE, marker.timestamp)
            .putLong(KEY_CURSOR_ID, marker.rowId)
            .putBoolean(KEY_REACHED_END, reachedEnd)
            .apply()
    }

    private class CloudPublisher(
        private val context: Context,
        private val topic: String,
        private val password: String
    ) {
        private val gson = Gson()

        suspend fun publishAndAwaitAck(
            sms: SmsMessage,
            remarkPhone: String,
            progressListener: ProgressListener?
        ): PublishAckResult {
            val client = MqttSyncService.globalMqttClient
            if (client == null || !client.isConnected) {
                return PublishAckResult(false, "云端连接已断开，队列已暂停。")
            }

            val waiter = MqttSyncService.registerHistoryImportAckWaiter(sms.uuid)

            return try {
                val payload = SyncPayload(
                    sms = sms,
                    remarkPhone = remarkPhone,
                    deviceName = DeviceNameManager.getDisplayName(context),
                    silentSync = true,
                    historyImport = true
                )
                val encrypted = DsimCryptoUtils.encryptMessage(gson.toJson(payload), password)
                if (encrypted == "ENCRYPTION_ERROR") {
                    return PublishAckResult(false, "历史短信加密失败，队列已暂停。")
                }

                val message = MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                }
                client.publish(topic, message)

                val deadlineAt = System.currentTimeMillis() + HISTORY_ACK_TIMEOUT_MS
                while (System.currentTimeMillis() < deadlineAt) {
                    if (progressListener?.isPauseRequested() == true) {
                        return PublishAckResult(
                            success = false,
                            detail = "已手动暂停，队列会从当前这条继续。",
                            wasPaused = true
                        )
                    }

                    val remaining = deadlineAt - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        break
                    }

                    val ack = withTimeoutOrNull(min(remaining, 500L)) {
                        waiter.await()
                    }
                    if (ack != null) {
                        if (!ack.success) {
                            val deviceLabel = ack.deviceName?.takeIf { it.isNotBlank() } ?: "对端设备"
                            val detail = ack.message?.takeIf { it.isNotBlank() } ?: "对端写入失败"
                            return PublishAckResult(false, "$deviceLabel 未确认成功：$detail")
                        }
                        return PublishAckResult(true)
                    }
                    delay(120L)
                }
                PublishAckResult(false, "等待云端确认超时，已停在 ${sms.address}。")
            } catch (e: Exception) {
                PublishAckResult(false, "发送历史短信失败：${e.message ?: "unknown error"}")
            } finally {
                MqttSyncService.clearHistoryImportAckWaiter(sms.uuid)
            }
        }
    }
}
