package com.example.dsim

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.dsim.database.DeviceProfile
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object HistorySyncQueueManager {

    const val STATUS_IDLE = "IDLE"
    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"

    private const val PREFS_NAME = "dSIM_HISTORY_QUEUE_PREFS"
    private const val KEY_ALLOW_REMOTE_START = "ALLOW_REMOTE_START"
    private const val KEY_QUEUE_ID = "QUEUE_ID"
    private const val KEY_QUEUE_STATUS = "QUEUE_STATUS"
    private const val KEY_QUEUE_POSITION = "QUEUE_POSITION"
    private const val KEY_QUEUE_TOTAL = "QUEUE_TOTAL"
    private const val KEY_QUEUE_REQUESTED_AT = "QUEUE_REQUESTED_AT"
    private const val KEY_QUEUE_REQUESTED_BY_DEVICE = "QUEUE_REQUESTED_BY_DEVICE"
    private const val KEY_QUEUE_REQUESTED_BY_NAME = "QUEUE_REQUESTED_BY_NAME"
    private const val KEY_QUEUE_LABEL = "QUEUE_LABEL"
    private const val KEY_QUEUE_DETAIL = "QUEUE_DETAIL"
    private const val KEY_QUEUE_PROGRESS_CURRENT = "QUEUE_PROGRESS_CURRENT"
    private const val KEY_QUEUE_PROGRESS_TOTAL = "QUEUE_PROGRESS_TOTAL"
    private const val KEY_QUEUE_UPDATED_AT = "QUEUE_UPDATED_AT"
    private const val KEY_LAST_BROADCAST_AT = "LAST_BROADCAST_AT"

    private const val SNAPSHOT_BROADCAST_MIN_INTERVAL_MS = 1200L

    data class QueueTarget(
        val deviceId: String,
        val deviceName: String,
        val position: Int
    )

    data class LocalQueueSnapshot(
        val allowsRemoteStart: Boolean = true,
        val queueId: String = "",
        val status: String = STATUS_IDLE,
        val position: Int? = null,
        val totalDevices: Int = 0,
        val requestedAt: Long = 0L,
        val requestedByDeviceId: String = "",
        val requestedByDeviceName: String = "",
        val label: String = "",
        val detail: String = "",
        val progressCurrent: Int = 0,
        val progressTotal: Int = 0,
        val updatedAt: Long = 0L
    ) {
        fun isActive(): Boolean = status != STATUS_IDLE || queueId.isNotBlank()
    }

    suspend fun enqueueLocalOnly(context: Context) {
        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        val deviceName = DeviceNameManager.getDisplayName(context)
        val queueId = "local_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        saveLocalQueueSnapshot(
            context,
            getLocalQueueSnapshot(context).copy(
                queueId = queueId,
                status = STATUS_QUEUED,
                position = 1,
                totalDevices = 1,
                requestedAt = now,
                requestedByDeviceId = localDeviceId,
                requestedByDeviceName = deviceName,
                label = "排队中",
                detail = "等待开始历史同步",
                progressCurrent = 0,
                progressTotal = 0,
                updatedAt = now
            )
        )
        HistoryQueueNotificationHelper.refresh(context)
        refreshLocalProfile(context)
        maybeBroadcastLocalSnapshot(context, force = true)
        evaluateAndMaybeStartLocal(context)
    }

    suspend fun handleQueueBatch(
        context: Context,
        queueId: String,
        createdAt: Long,
        requestedByDeviceId: String,
        requestedByDeviceName: String,
        targets: List<QueueTarget>
    ) {
        if (queueId.isBlank() || targets.isEmpty()) {
            return
        }

        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        val dao = DsimDatabase.getDatabase(context).dsimDao()

        targets.forEach { target ->
            val existing = dao.getDeviceProfile(target.deviceId) ?: return@forEach
            val now = System.currentTimeMillis()
            dao.saveDeviceProfile(
                existing.copy(
                    historyQueueId = queueId,
                    historyQueueStatus = STATUS_QUEUED,
                    historyQueuePosition = target.position,
                    historyQueueLabel = if (target.position == 1) "等待开始" else "排队第 ${target.position}",
                    historyQueueDetail = buildRequesterDetail(requestedByDeviceName, createdAt),
                    historyQueueProgressCurrent = 0,
                    historyQueueProgressTotal = 0,
                    historyQueueUpdatedAt = now
                )
            )
        }

        val localTarget = targets.firstOrNull { it.deviceId == localDeviceId } ?: run {
            return
        }

        if (!isRemoteStartAllowed(context) && requestedByDeviceId != localDeviceId) {
            return
        }

        val current = getLocalQueueSnapshot(context)
        if (current.status == STATUS_RUNNING && current.queueId != queueId) {
            return
        }

        saveLocalQueueSnapshot(
            context,
            current.copy(
                queueId = queueId,
                status = STATUS_QUEUED,
                position = localTarget.position,
                totalDevices = targets.size,
                requestedAt = createdAt,
                requestedByDeviceId = requestedByDeviceId,
                requestedByDeviceName = requestedByDeviceName,
                label = if (localTarget.position == 1) "等待开始" else "排队第 ${localTarget.position}",
                detail = buildRequesterDetail(requestedByDeviceName, createdAt),
                progressCurrent = 0,
                progressTotal = 0,
                updatedAt = System.currentTimeMillis()
            )
        )
        HistoryQueueNotificationHelper.refresh(context)
        refreshLocalProfile(context)
        evaluateAndMaybeStartLocal(context)
    }

    suspend fun updateFromImportState(
        context: Context,
        state: HistoryImportUiState
    ) {
        val current = getLocalQueueSnapshot(context)
        if (!current.isActive() && !state.isRunning && !state.hasSnapshot()) {
            return
        }

        val now = System.currentTimeMillis()
        val inferredQueueId = current.queueId.ifBlank { "local_${UUID.randomUUID()}" }
        val inferredPosition = current.position ?: 1
        val inferredTotal = current.totalDevices.takeIf { it > 0 } ?: 1
        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        val localDeviceName = DeviceNameManager.getDisplayName(context)

        val nextStatus = when {
            state.isRunning -> STATUS_RUNNING
            state.isPaused -> STATUS_PAUSED
            current.status == STATUS_RUNNING && state.stage.contains("完成") -> STATUS_COMPLETED
            current.status == STATUS_RUNNING && state.stage.contains("失败") -> STATUS_FAILED
            current.status == STATUS_QUEUED -> STATUS_QUEUED
            current.status == STATUS_COMPLETED -> STATUS_COMPLETED
            state.hasSnapshot() -> STATUS_PAUSED
            else -> STATUS_IDLE
        }

        val nextLabel = when (nextStatus) {
            STATUS_QUEUED -> if (inferredPosition == 1) "等待开始" else "排队第 $inferredPosition"
            STATUS_RUNNING -> state.stage.ifBlank { "同步中" }
            STATUS_PAUSED -> state.stage.ifBlank { "已暂停" }
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> state.stage.ifBlank { "异常暂停" }
            else -> ""
        }

        val nextDetail = when {
            nextStatus == STATUS_QUEUED -> buildRequesterDetail(
                current.requestedByDeviceName.ifBlank { localDeviceName },
                current.requestedAt.takeIf { it > 0L } ?: now
            )
            state.detailMessage.isNotBlank() -> state.detailMessage
            current.detail.isNotBlank() -> current.detail
            else -> ""
        }

        val snapshot = current.copy(
            queueId = inferredQueueId.takeIf { nextStatus != STATUS_IDLE }.orEmpty(),
            status = nextStatus,
            position = if (nextStatus == STATUS_IDLE) null else inferredPosition,
            totalDevices = if (nextStatus == STATUS_IDLE) 0 else inferredTotal,
            requestedAt = current.requestedAt.takeIf { it > 0L } ?: now,
            requestedByDeviceId = current.requestedByDeviceId.ifBlank { localDeviceId },
            requestedByDeviceName = current.requestedByDeviceName.ifBlank { localDeviceName },
            label = nextLabel,
            detail = nextDetail,
            progressCurrent = when {
                state.systemCount > 0 -> state.scannedCount.coerceAtMost(state.systemCount)
                nextStatus == STATUS_COMPLETED -> state.scannedCount
                else -> current.progressCurrent
            },
            progressTotal = when {
                state.systemCount > 0 -> state.systemCount
                nextStatus == STATUS_COMPLETED -> state.scannedCount
                else -> current.progressTotal
            },
            updatedAt = now
        )

        saveLocalQueueSnapshot(context, snapshot)
        HistoryQueueNotificationHelper.refresh(context)
        refreshLocalProfile(context)
        maybeBroadcastLocalSnapshot(context, force = state.isRunning.not())
    }

    suspend fun evaluateAndMaybeStartLocal(context: Context) {
        val local = getLocalQueueSnapshot(context)
        if (local.status != STATUS_QUEUED) {
            return
        }
        val allowOfflineSingleDeviceRun = (local.totalDevices <= 1) && (local.position ?: 1) == 1
        if (!MqttSyncService.isConnected() && !allowOfflineSingleDeviceRun) {
            return
        }
        if (SystemHistoryImportService.stateFlow.value.isRunning) {
            return
        }

        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val queueProfiles = dao.getAllDeviceProfiles()
            .filter { it.historyQueueId == local.queueId && it.historyQueueStatus != STATUS_IDLE }
            .sortedWith(
                compareBy<DeviceProfile> { it.historyQueuePosition ?: Int.MAX_VALUE }
                    .thenBy { it.deviceName }
            )

        val runningPeer = queueProfiles.firstOrNull {
            it.deviceId != localDeviceId &&
                it.historyQueueStatus == STATUS_RUNNING &&
                DeviceDirectoryManager.isOnline(it)
        }
        if (runningPeer != null) {
            return
        }

        val firstRunnable = queueProfiles.firstOrNull { profile ->
            when (profile.historyQueueStatus) {
                STATUS_QUEUED, STATUS_RUNNING, STATUS_PAUSED -> profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile)
                else -> false
            }
        } ?: return

        if (firstRunnable.deviceId != localDeviceId) {
            return
        }

        SystemHistoryImportService.startImport(context)
    }

    suspend fun publishQueueBatchRequest(
        context: Context,
        targets: List<DeviceProfile>
    ): String = withContext(Dispatchers.IO) {
        val client = MqttSyncService.globalMqttClient
        if (client?.isConnected != true) {
            throw IllegalStateException("云端未连接")
        }

        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val topic = prefs.getString("TOPIC", "").orEmpty().trim()
        val password = prefs.getString("PASSWORD", "").orEmpty().trim()
        if (topic.isBlank() || password.isBlank()) {
            throw IllegalStateException("请先配置云端通道")
        }

        val queueId = "queue_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val createdAt = System.currentTimeMillis()
        val requesterId = HardwareProbeUtils.getDeviceId(context)
        val requesterName = DeviceNameManager.getDisplayName(context)
        val remoteTargets = targets
            .filter { it.deviceId != requesterId && !it.isLocalDevice }
            .distinctBy { it.deviceId }
        if (remoteTargets.isEmpty()) {
            throw IllegalArgumentException("请至少选择 1 台远程设备")
        }
        val queueTargets = remoteTargets.mapIndexed { index, profile ->
            QueueTarget(
                deviceId = profile.deviceId,
                deviceName = profile.deviceName,
                position = index + 1
            )
        }

        val payloadJson = JSONObject().apply {
            put("action", MqttSyncService.MQTT_ACTION_HISTORY_QUEUE_BATCH)
            put("queueId", queueId)
            put("createdAt", createdAt)
            put("requestedByDeviceId", requesterId)
            put("requestedByDeviceName", requesterName)
            put(
                "targets",
                JSONArray().apply {
                    queueTargets.forEach { target ->
                        put(
                            JSONObject().apply {
                                put("deviceId", target.deviceId)
                                put("deviceName", target.deviceName)
                                put("position", target.position)
                            }
                        )
                    }
                }
            )
        }.toString()

        val encrypted = DsimCryptoUtils.encryptMessage(payloadJson, password)
        if (encrypted == "ENCRYPTION_ERROR") {
            throw IllegalStateException("历史同步队列加密失败")
        }

        val message = MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply {
            qos = 1
        }
        client.publish(topic, message)

        handleQueueBatch(
            context = context,
            queueId = queueId,
            createdAt = createdAt,
            requestedByDeviceId = requesterId,
            requestedByDeviceName = requesterName,
            targets = queueTargets
        )
        HistoryQueueNotificationHelper.rememberRemoteQueue(
            context = context,
            queueId = queueId,
            requestedAt = createdAt,
            targetCount = queueTargets.size,
            targetNames = queueTargets.joinToString("、") { it.deviceName }
        )
        HistoryQueueNotificationHelper.refresh(context)
        maybeBroadcastLocalSnapshot(context, force = true)
        queueId
    }

    fun isRemoteStartAllowed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ALLOW_REMOTE_START, true)
    }

    fun setRemoteStartAllowed(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ALLOW_REMOTE_START, enabled)
            .apply()
    }

    fun getLocalQueueSnapshot(context: Context): LocalQueueSnapshot {
        val prefs = prefs(context)
        val rawQueueId = prefs.getString(KEY_QUEUE_ID, "").orEmpty()
        val rawStatus = prefs.getString(KEY_QUEUE_STATUS, STATUS_IDLE).orEmpty()
        return LocalQueueSnapshot(
            allowsRemoteStart = prefs.getBoolean(KEY_ALLOW_REMOTE_START, true),
            queueId = rawQueueId,
            status = rawStatus.ifBlank { STATUS_IDLE },
            position = prefs.getInt(KEY_QUEUE_POSITION, -1).takeIf { it > 0 },
            totalDevices = prefs.getInt(KEY_QUEUE_TOTAL, 0),
            requestedAt = prefs.getLong(KEY_QUEUE_REQUESTED_AT, 0L),
            requestedByDeviceId = prefs.getString(KEY_QUEUE_REQUESTED_BY_DEVICE, "").orEmpty(),
            requestedByDeviceName = prefs.getString(KEY_QUEUE_REQUESTED_BY_NAME, "").orEmpty(),
            label = prefs.getString(KEY_QUEUE_LABEL, "").orEmpty(),
            detail = prefs.getString(KEY_QUEUE_DETAIL, "").orEmpty(),
            progressCurrent = prefs.getInt(KEY_QUEUE_PROGRESS_CURRENT, 0),
            progressTotal = prefs.getInt(KEY_QUEUE_PROGRESS_TOTAL, 0),
            updatedAt = prefs.getLong(KEY_QUEUE_UPDATED_AT, 0L)
        )
    }

    fun buildQueueBadgeText(profile: DeviceProfile, now: Long): String {
        val isOnline = profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile, now)
        return when {
            !isOnline && profile.historyQueueStatus in setOf(STATUS_QUEUED, STATUS_RUNNING) -> "等待上线"
            profile.historyQueueStatus == STATUS_RUNNING && profile.historyQueueProgressTotal > 0 ->
                "同步中 ${profile.historyQueueProgressCurrent}/${profile.historyQueueProgressTotal}"
            profile.historyQueueStatus == STATUS_RUNNING -> "同步中"
            profile.historyQueueStatus == STATUS_QUEUED && profile.historyQueuePosition != null ->
                "排队第 ${profile.historyQueuePosition}"
            profile.historyQueueStatus == STATUS_PAUSED -> "已暂停"
            profile.historyQueueStatus == STATUS_COMPLETED -> "已完成"
            profile.historyQueueStatus == STATUS_FAILED -> "异常暂停"
            else -> "空闲"
        }
    }

    fun buildQueueDetail(profile: DeviceProfile, now: Long): String {
        val isOnline = profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile, now)
        return when {
            !isOnline && profile.historyQueueStatus in setOf(STATUS_QUEUED, STATUS_RUNNING) ->
                "设备离线，恢复在线后可继续参与队列"
            profile.historyQueueDetail.isNotBlank() -> profile.historyQueueDetail
            profile.historyQueueLabel.isNotBlank() -> profile.historyQueueLabel
            else -> "当前未加入历史同步队列"
        }
    }

    fun queueStatusColor(profile: DeviceProfile, now: Long): String {
        val isOnline = profile.isLocalDevice || DeviceDirectoryManager.isOnline(profile, now)
        return when {
            !isOnline && profile.historyQueueStatus in setOf(STATUS_QUEUED, STATUS_RUNNING) -> "#9A6700"
            profile.historyQueueStatus == STATUS_RUNNING -> "#2457F5"
            profile.historyQueueStatus == STATUS_QUEUED -> "#7C3AED"
            profile.historyQueueStatus == STATUS_PAUSED -> "#C67A00"
            profile.historyQueueStatus == STATUS_COMPLETED -> "#1F8A4D"
            profile.historyQueueStatus == STATUS_FAILED -> "#8A1F2D"
            else -> "#64748B"
        }
    }

    suspend fun maybeBroadcastLocalSnapshot(context: Context, force: Boolean = false) {
        if (!MqttSyncService.isConnected()) {
            return
        }
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val lastBroadcastAt = prefs.getLong(KEY_LAST_BROADCAST_AT, 0L)
        if (!force && now - lastBroadcastAt < SNAPSHOT_BROADCAST_MIN_INTERVAL_MS) {
            return
        }
        prefs.edit().putLong(KEY_LAST_BROADCAST_AT, now).apply()
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, MqttSyncService::class.java).apply {
                action = MqttSyncService.ACTION_BROADCAST_DEVICE_PROFILE
            }
        )
    }

    private suspend fun refreshLocalProfile(context: Context) {
        withContext(Dispatchers.IO) {
            DeviceDirectoryManager.saveLocalSnapshot(context)
        }
    }

    private fun saveLocalQueueSnapshot(context: Context, snapshot: LocalQueueSnapshot) {
        val editor = prefs(context).edit()
            .putBoolean(KEY_ALLOW_REMOTE_START, snapshot.allowsRemoteStart)
            .putString(KEY_QUEUE_ID, snapshot.queueId)
            .putString(KEY_QUEUE_STATUS, snapshot.status)
            .putInt(KEY_QUEUE_POSITION, snapshot.position ?: -1)
            .putInt(KEY_QUEUE_TOTAL, snapshot.totalDevices)
            .putLong(KEY_QUEUE_REQUESTED_AT, snapshot.requestedAt)
            .putString(KEY_QUEUE_REQUESTED_BY_DEVICE, snapshot.requestedByDeviceId)
            .putString(KEY_QUEUE_REQUESTED_BY_NAME, snapshot.requestedByDeviceName)
            .putString(KEY_QUEUE_LABEL, snapshot.label)
            .putString(KEY_QUEUE_DETAIL, snapshot.detail)
            .putInt(KEY_QUEUE_PROGRESS_CURRENT, snapshot.progressCurrent)
            .putInt(KEY_QUEUE_PROGRESS_TOTAL, snapshot.progressTotal)
            .putLong(KEY_QUEUE_UPDATED_AT, snapshot.updatedAt)
        editor.apply()
    }

    private fun buildRequesterDetail(requestedByName: String, createdAt: Long): String {
        val requester = requestedByName.ifBlank { "本机" }
        val formatted = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(createdAt))
        return "由 $requester 发起，加入队列时间 $formatted"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
