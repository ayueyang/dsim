package com.example.dsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.dsim.database.DeviceProfile
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HistoryQueueNotificationHelper {

    private const val CHANNEL_ID = "dsim_history_import_queue"
    const val EXECUTION_NOTIFICATION_ID = 1088
    private const val REQUESTER_NOTIFICATION_ID = 1089
    private const val PREFS_NAME = "dSIM_HISTORY_QUEUE_NOTIFY_PREFS"
    private const val KEY_REMOTE_QUEUE_ID = "REMOTE_QUEUE_ID"
    private const val KEY_REMOTE_QUEUE_REQUESTED_AT = "REMOTE_QUEUE_REQUESTED_AT"
    private const val KEY_REMOTE_QUEUE_TARGET_COUNT = "REMOTE_QUEUE_TARGET_COUNT"
    private const val KEY_REMOTE_QUEUE_TARGET_NAMES = "REMOTE_QUEUE_TARGET_NAMES"
    private const val REMOTE_QUEUE_GRACE_WINDOW_MS = 2 * 60_000L

    private data class RemoteQueueMonitor(
        val queueId: String = "",
        val requestedAt: Long = 0L,
        val targetCount: Int = 0,
        val targetNames: String = ""
    ) {
        fun isActive(): Boolean = queueId.isNotBlank()
    }

    private data class RemoteQueueInfo(
        val title: String,
        val compactLine: String,
        val detailLines: List<String>,
        val ongoing: Boolean,
        val showIndeterminateProgress: Boolean,
        val progressCurrent: Int = 0,
        val progressTotal: Int = 0
    )

    suspend fun refresh(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val state = SystemHistoryImportService.stateFlow.value
        val localSnapshot = HistorySyncQueueManager.getLocalQueueSnapshot(context)
        val remoteMonitor = getRemoteQueueMonitor(context)
        val remoteInfo = if (remoteMonitor.isActive()) {
            loadRemoteQueueInfo(context, remoteMonitor)
        } else {
            null
        }

        val executionNotification = if (shouldShowExecutionNotification(state, localSnapshot)) {
            buildExecutionNotification(context, state, localSnapshot)
        } else {
            null
        }
        val requesterNotification = remoteInfo?.let { buildRequesterNotification(context, it) }

        if (executionNotification != null) {
            manager.notify(EXECUTION_NOTIFICATION_ID, executionNotification)
        } else {
            manager.cancel(EXECUTION_NOTIFICATION_ID)
        }

        if (requesterNotification != null) {
            manager.notify(REQUESTER_NOTIFICATION_ID, requesterNotification)
        } else {
            manager.cancel(REQUESTER_NOTIFICATION_ID)
        }
    }

    fun rememberRemoteQueue(
        context: Context,
        queueId: String,
        requestedAt: Long,
        targetCount: Int,
        targetNames: String
    ) {
        prefs(context).edit()
            .putString(KEY_REMOTE_QUEUE_ID, queueId)
            .putLong(KEY_REMOTE_QUEUE_REQUESTED_AT, requestedAt)
            .putInt(KEY_REMOTE_QUEUE_TARGET_COUNT, targetCount)
            .putString(KEY_REMOTE_QUEUE_TARGET_NAMES, targetNames)
            .apply()
    }

    fun buildExecutionNotification(
        context: Context,
        state: HistoryImportUiState,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot
    ): Notification {
        val requestedBySelf = isRequestedBySelf(context, snapshot)
        val requesterName = snapshot.requestedByDeviceName.ifBlank { "其他设备" }
        val displayRequesterName = if (requestedBySelf) {
            "本机"
        } else {
            PrivacyModeManager.displayNotificationDeviceName(context, requesterName)
        }
        val sourceText = if (requestedBySelf) {
            "发起设备：本机"
        } else {
            "发起设备：$displayRequesterName"
        }
        val title = buildExecutionTitle(
            state = state,
            snapshot = snapshot,
            requestedBySelf = requestedBySelf,
            requesterName = displayRequesterName
        )
        val compactLine = buildExecutionCompactLine(state, snapshot)
        val detailLines = buildList {
            add(sourceText)
            add(buildExecutionDetailLine(state, snapshot))
            buildExecutionQueueLine(snapshot)?.let(::add)
            buildExecutionAddressLine(context, state)?.let(::add)
        }.filter { it.isNotBlank() }

        val contentIntent = buildContentIntent(context)
        val pauseIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, SystemHistoryImportService::class.java).apply {
                action = SystemHistoryImportService.ACTION_PAUSE_IMPORT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(compactLine)
            .setSubText(if (requestedBySelf) "本机执行" else "执行方")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailLines.joinToString("\n")))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state.isRunning || snapshot.status == HistorySyncQueueManager.STATUS_QUEUED)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)

        when {
            state.isRunning && state.systemCount > 0 -> {
                builder.setProgress(
                    state.systemCount,
                    state.scannedCount.coerceAtMost(state.systemCount),
                    false
                )
            }

            state.isRunning -> {
                builder.setProgress(0, 0, true)
            }

            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED -> {
                builder.setProgress(0, 0, true)
            }

            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED && snapshot.progressTotal > 0 -> {
                builder.setProgress(
                    snapshot.progressTotal,
                    snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal),
                    false
                )
            }

            else -> {
                builder.setProgress(0, 0, false)
            }
        }

        if (state.isRunning) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                pauseIntent
            )
        }

        return builder.build()
    }

    private fun buildRequesterNotification(
        context: Context,
        remoteInfo: RemoteQueueInfo
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(remoteInfo.title)
            .setContentText(remoteInfo.compactLine)
            .setSubText("你发起的同步")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    remoteInfo.detailLines.filter { it.isNotBlank() }.joinToString("\n")
                )
            )
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(remoteInfo.ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(buildContentIntent(context))

        when {
            remoteInfo.ongoing && remoteInfo.showIndeterminateProgress -> {
                builder.setProgress(0, 0, true)
            }

            remoteInfo.ongoing && remoteInfo.progressTotal > 0 -> {
                builder.setProgress(
                    remoteInfo.progressTotal,
                    remoteInfo.progressCurrent.coerceAtMost(remoteInfo.progressTotal),
                    false
                )
            }

            else -> {
                builder.setProgress(0, 0, false)
            }
        }

        return builder.build()
    }

    private fun shouldShowExecutionNotification(
        state: HistoryImportUiState,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot
    ): Boolean {
        return state.hasSnapshot() || snapshot.isActive()
    }

    private fun buildExecutionTitle(
        state: HistoryImportUiState,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot,
        requestedBySelf: Boolean,
        requesterName: String
    ): String {
        if (requestedBySelf) {
            return when {
                state.isRunning -> "本机历史同步中"
                snapshot.status == HistorySyncQueueManager.STATUS_QUEUED &&
                    (snapshot.position ?: 1) > 1 -> "本机历史同步排队中"

                snapshot.status == HistorySyncQueueManager.STATUS_QUEUED -> "本机历史同步等待中"
                snapshot.status == HistorySyncQueueManager.STATUS_PAUSED -> "本机历史同步已暂停"
                snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED -> "本机历史同步已完成"
                snapshot.status == HistorySyncQueueManager.STATUS_FAILED -> "本机历史同步失败"
                else -> "本机历史同步"
            }
        }

        return when {
            state.isRunning -> "给 $requesterName 同步中"
            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED &&
                (snapshot.position ?: 1) > 1 -> "给 $requesterName 排队中"

            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED -> "给 $requesterName 等待中"
            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED -> "给 $requesterName 已暂停"
            snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED -> "给 $requesterName 已完成"
            snapshot.status == HistorySyncQueueManager.STATUS_FAILED -> "给 $requesterName 失败"
            else -> "给 $requesterName 同步"
        }
    }

    private fun buildExecutionCompactLine(
        state: HistoryImportUiState,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot
    ): String {
        return when {
            state.isRunning && state.systemCount > 0 ->
                "${state.scannedCount.coerceAtMost(state.systemCount)}/${state.systemCount} 条"

            state.isRunning -> "正在处理"
            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED &&
                (snapshot.position ?: 1) > 1 -> "前面 ${((snapshot.position ?: 1) - 1).coerceAtLeast(0)} 台"

            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED -> "等待开始"
            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED && snapshot.progressTotal > 0 ->
                "${snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal)}/${snapshot.progressTotal} 条"

            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED -> "已暂停"
            snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED && snapshot.progressTotal > 0 ->
                "${snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal)}/${snapshot.progressTotal} 条"

            snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED -> "已完成"
            snapshot.status == HistorySyncQueueManager.STATUS_FAILED -> "执行中断"
            else -> "查看详情"
        }
    }

    private fun buildExecutionDetailLine(
        state: HistoryImportUiState,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot
    ): String {
        return when {
            state.isRunning && state.systemCount > 0 ->
                "本机已处理 ${state.scannedCount.coerceAtMost(state.systemCount)}/${state.systemCount} 条"

            state.isRunning -> "本机正在处理历史短信"
            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED &&
                (snapshot.position ?: 1) > 1 -> "前面还有 ${((snapshot.position ?: 1) - 1).coerceAtLeast(0)} 台设备"

            snapshot.status == HistorySyncQueueManager.STATUS_QUEUED -> "已收到任务，等待开始"
            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED && snapshot.progressTotal > 0 ->
                "当前停在 ${snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal)}/${snapshot.progressTotal} 条"

            snapshot.status == HistorySyncQueueManager.STATUS_PAUSED -> "任务已暂停，可稍后继续"
            snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED && snapshot.progressTotal > 0 ->
                "本机已处理 ${snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal)}/${snapshot.progressTotal} 条"

            snapshot.status == HistorySyncQueueManager.STATUS_COMPLETED -> "本机已完成同步"
            snapshot.status == HistorySyncQueueManager.STATUS_FAILED && snapshot.progressTotal > 0 ->
                "中断前已处理 ${snapshot.progressCurrent.coerceAtMost(snapshot.progressTotal)}/${snapshot.progressTotal} 条"

            snapshot.status == HistorySyncQueueManager.STATUS_FAILED -> "任务执行中断，请点开查看详情"
            else -> "当前没有执行中的任务"
        }
    }

    private fun buildExecutionQueueLine(snapshot: HistorySyncQueueManager.LocalQueueSnapshot): String? {
        val position = snapshot.position ?: return null
        if (snapshot.totalDevices <= 1) {
            return null
        }
        return "队列顺序：第 $position / ${snapshot.totalDevices} 台"
    }

    private fun buildExecutionAddressLine(context: Context, state: HistoryImportUiState): String? {
        if (!state.isRunning) {
            return null
        }
        return state.currentAddress.takeIf { it.isNotBlank() }?.let {
            "当前号码：${PrivacyModeManager.displayOwnPhone(context, it)}"
        }
    }

    private suspend fun loadRemoteQueueInfo(
        context: Context,
        monitor: RemoteQueueMonitor
    ): RemoteQueueInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val profiles = DsimDatabase.getDatabase(context).dsimDao()
            .getDeviceProfilesByQueueId(monitor.queueId)
            .filterNot { it.isLocalDevice }
            .sortedWith(
                compareBy<DeviceProfile> { it.historyQueuePosition ?: Int.MAX_VALUE }
                    .thenBy { it.deviceName }
            )

        if (profiles.isEmpty()) {
            val stillWaiting = monitor.requestedAt > 0L &&
                now - monitor.requestedAt <= REMOTE_QUEUE_GRACE_WINDOW_MS
            if (!stillWaiting) {
                clearRemoteQueueMonitor(context)
                return@withContext null
            }

            val targetCount = monitor.targetCount.coerceAtLeast(1)
            val displayTargetName = PrivacyModeManager.displayNotificationDeviceName(
                context,
                monitor.targetNames
            )
            val title = if (targetCount == 1 && monitor.targetNames.isNotBlank()) {
                "你让 $displayTargetName 等待中"
            } else {
                "你让 $targetCount 台设备同步"
            }
            return@withContext RemoteQueueInfo(
                title = title,
                compactLine = if (targetCount == 1) "等待开始" else "$targetCount 台等待接单",
                detailLines = buildList {
                    add(if (targetCount == 1 && monitor.targetNames.isNotBlank()) {
                        "目标设备：$displayTargetName"
                    } else {
                        "目标设备：共 $targetCount 台"
                    })
                    monitor.targetNames.takeIf { it.isNotBlank() }?.let {
                        add("设备列表：${PrivacyModeManager.displayMessageText(context, it)}")
                    }
                },
                ongoing = true,
                showIndeterminateProgress = true
            )
        }

        val running = profiles.filter { it.historyQueueStatus == HistorySyncQueueManager.STATUS_RUNNING }
        val queued = profiles.filter { it.historyQueueStatus == HistorySyncQueueManager.STATUS_QUEUED }
        val paused = profiles.filter { it.historyQueueStatus == HistorySyncQueueManager.STATUS_PAUSED }
        val failed = profiles.filter { it.historyQueueStatus == HistorySyncQueueManager.STATUS_FAILED }
        val completed = profiles.filter { it.historyQueueStatus == HistorySyncQueueManager.STATUS_COMPLETED }
        val progressProfile = running.firstOrNull()
        val nextQueued = queued.firstOrNull()
        val totalTargets = maxOf(profiles.size, monitor.targetCount.coerceAtLeast(1))
        val singleTargetName = when {
            totalTargets == 1 && profiles.isNotEmpty() -> profiles.first().deviceName
            totalTargets == 1 && monitor.targetNames.isNotBlank() -> monitor.targetNames
            else -> null
        }
        val displaySingleTargetName = singleTargetName?.let {
            PrivacyModeManager.displayNotificationDeviceName(context, it)
        }

        val title = buildRequesterTitle(
            singleTargetName = displaySingleTargetName,
            totalTargets = totalTargets,
            progressProfile = progressProfile,
            nextQueued = nextQueued,
            completedCount = completed.size,
            pausedCount = paused.size,
            failedCount = failed.size
        )
        val compactLine = buildRequesterCompactLine(
            singleTargetName = displaySingleTargetName,
            totalTargets = totalTargets,
            progressProfile = progressProfile,
            nextQueued = nextQueued,
            completedCount = completed.size,
            runningCount = running.size,
            queuedCount = queued.size,
            pausedCount = paused.size,
            failedCount = failed.size
        )

        val detailLines = buildList {
            add(if (displaySingleTargetName != null) {
                "目标设备：$displaySingleTargetName"
            } else {
                "目标设备：共 $totalTargets 台"
            })

            if (progressProfile != null) {
                val progressDeviceName = displayHistoryProfileName(context, progressProfile)
                val queueSuffix = progressProfile.historyQueuePosition?.let {
                    "（第 $it / $totalTargets 台）"
                }.orEmpty()
                if (progressProfile.historyQueueProgressTotal > 0) {
                    add(
                        "正在执行：$progressDeviceName$queueSuffix，已处理 " +
                            "${progressProfile.historyQueueProgressCurrent.coerceAtMost(progressProfile.historyQueueProgressTotal)}/" +
                            "${progressProfile.historyQueueProgressTotal} 条"
                    )
                } else {
                    add("正在执行：$progressDeviceName$queueSuffix")
                }
            } else if (nextQueued != null) {
                val position = nextQueued.historyQueuePosition ?: 1
                val queuedDeviceName = displayHistoryProfileName(context, nextQueued)
                add(
                    if (position > 1) {
                        "当前排队：$queuedDeviceName（第 $position / $totalTargets 台）"
                    } else {
                        "等待执行：$queuedDeviceName"
                    }
                )
            } else if (completed.size >= totalTargets) {
                add("所有目标设备都已完成")
            } else {
                add(buildRequesterStatusSummary(completed.size, running.size, queued.size, paused.size, failed.size))
            }

            if (profiles.size <= 3) {
                profiles.forEachIndexed { index, profile ->
                    val deviceName = displayHistoryProfileName(context, profile, index)
                    add("$deviceName：${buildRequesterDeviceStatus(profile, now)}")
                }
            }
        }

        val ongoing = running.isNotEmpty() || queued.isNotEmpty()
        val multiTarget = totalTargets > 1
        val progressTotal = when {
            !ongoing -> 0
            multiTarget -> totalTargets
            progressProfile != null -> progressProfile.historyQueueProgressTotal
            else -> 0
        }
        val progressCurrent = when {
            !ongoing -> 0
            multiTarget -> completed.size.coerceAtMost(totalTargets)
            progressProfile != null -> progressProfile.historyQueueProgressCurrent
            else -> 0
        }
        val showIndeterminateProgress = ongoing && progressCurrent == 0

        RemoteQueueInfo(
            title = title,
            compactLine = compactLine,
            detailLines = detailLines,
            ongoing = ongoing,
            showIndeterminateProgress = showIndeterminateProgress,
            progressCurrent = progressCurrent,
            progressTotal = progressTotal
        )
    }

    private fun buildRequesterTitle(
        singleTargetName: String?,
        totalTargets: Int,
        progressProfile: DeviceProfile?,
        nextQueued: DeviceProfile?,
        completedCount: Int,
        pausedCount: Int,
        failedCount: Int
    ): String {
        if (singleTargetName != null) {
            return when {
                progressProfile != null -> "你让 $singleTargetName 同步中"
                nextQueued != null && (nextQueued.historyQueuePosition ?: 1) > 1 -> "你让 $singleTargetName 排队中"
                nextQueued != null -> "你让 $singleTargetName 等待中"
                failedCount > 0 -> "你让 $singleTargetName 失败"
                pausedCount > 0 -> "你让 $singleTargetName 已暂停"
                completedCount > 0 -> "你让 $singleTargetName 已完成"
                else -> "你让 $singleTargetName 同步"
            }
        }

        return when {
            progressProfile != null || nextQueued != null -> "你让 $totalTargets 台设备同步"
            failedCount > 0 && completedCount == 0 && pausedCount == 0 -> "你让 $totalTargets 台设备失败"
            pausedCount > 0 && completedCount == 0 && failedCount == 0 -> "你让 $totalTargets 台设备已暂停"
            completedCount >= totalTargets -> "你让 $totalTargets 台设备已完成"
            else -> "你让 $totalTargets 台设备同步"
        }
    }

    private fun buildRequesterCompactLine(
        singleTargetName: String?,
        totalTargets: Int,
        progressProfile: DeviceProfile?,
        nextQueued: DeviceProfile?,
        completedCount: Int,
        runningCount: Int,
        queuedCount: Int,
        pausedCount: Int,
        failedCount: Int
    ): String {
        if (singleTargetName == null && totalTargets > 1) {
            return buildRequesterStatusSummary(
                completedCount = completedCount,
                runningCount = runningCount,
                queuedCount = queuedCount,
                pausedCount = pausedCount,
                failedCount = failedCount
            )
        }

        return when {
            progressProfile != null && progressProfile.historyQueueProgressTotal > 0 ->
                "${progressProfile.historyQueueProgressCurrent.coerceAtMost(progressProfile.historyQueueProgressTotal)}/${progressProfile.historyQueueProgressTotal} 条"

            progressProfile != null -> "正在同步"
            nextQueued != null && (nextQueued.historyQueuePosition ?: 1) > 1 ->
                "排队第 ${nextQueued.historyQueuePosition ?: 1}/$totalTargets 台"

            nextQueued != null -> "等待开始"
            failedCount > 0 && completedCount > 0 -> "部分失败"
            failedCount > 0 -> "同步失败"
            pausedCount > 0 -> "已暂停"
            completedCount > 0 -> "已完成"
            else -> "查看详情"
        }
    }

    private fun buildRequesterStatusSummary(
        completedCount: Int,
        runningCount: Int,
        queuedCount: Int,
        pausedCount: Int,
        failedCount: Int
    ): String {
        val parts = buildList {
            if (completedCount > 0) add("$completedCount 完成")
            if (runningCount > 0) add("$runningCount 同步中")
            if (queuedCount > 0) add("$queuedCount 排队")
            if (pausedCount > 0) add("$pausedCount 暂停")
            if (failedCount > 0) add("$failedCount 失败")
        }
        return parts.joinToString(" ").ifBlank { "等待开始" }
    }

    private fun buildRequesterDeviceStatus(profile: DeviceProfile, now: Long): String {
        val isOnline = DeviceDirectoryManager.isOnline(profile, now)
        return when {
            !isOnline && profile.historyQueueStatus in setOf(
                HistorySyncQueueManager.STATUS_QUEUED,
                HistorySyncQueueManager.STATUS_RUNNING
            ) -> "离线，等上线"

            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_RUNNING &&
                profile.historyQueueProgressTotal > 0 ->
                "已处理 ${profile.historyQueueProgressCurrent.coerceAtMost(profile.historyQueueProgressTotal)}/${profile.historyQueueProgressTotal} 条"

            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_RUNNING -> "正在同步"
            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_QUEUED &&
                (profile.historyQueuePosition ?: 1) > 1 -> "排队中"

            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_QUEUED -> "等待开始"
            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_PAUSED -> "已暂停"
            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_COMPLETED -> "已完成"
            profile.historyQueueStatus == HistorySyncQueueManager.STATUS_FAILED -> "失败"
            else -> "空闲"
        }
    }

    private fun displayHistoryProfileName(
        context: Context,
        profile: DeviceProfile,
        index: Int? = null
    ): String {
        return PrivacyModeManager.displayNotificationDeviceName(
            context,
            profile.deviceName,
            fallback = index?.plus(1)?.let { "第 $it 台设备" } ?: "远端设备"
        )
    }

    private fun isRequestedBySelf(
        context: Context,
        snapshot: HistorySyncQueueManager.LocalQueueSnapshot
    ): Boolean {
        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        return snapshot.requestedByDeviceId.isBlank() || snapshot.requestedByDeviceId == localDeviceId
    }

    private fun buildContentIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, SettingsActivity::class.java).apply {
                putExtra(SystemHistoryImportService.EXTRA_OPEN_QUEUE, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getRemoteQueueMonitor(context: Context): RemoteQueueMonitor {
        val prefs = prefs(context)
        return RemoteQueueMonitor(
            queueId = prefs.getString(KEY_REMOTE_QUEUE_ID, "").orEmpty(),
            requestedAt = prefs.getLong(KEY_REMOTE_QUEUE_REQUESTED_AT, 0L),
            targetCount = prefs.getInt(KEY_REMOTE_QUEUE_TARGET_COUNT, 0),
            targetNames = prefs.getString(KEY_REMOTE_QUEUE_TARGET_NAMES, "").orEmpty()
        )
    }

    private fun clearRemoteQueueMonitor(context: Context) {
        prefs(context).edit()
            .remove(KEY_REMOTE_QUEUE_ID)
            .remove(KEY_REMOTE_QUEUE_REQUESTED_AT)
            .remove(KEY_REMOTE_QUEUE_TARGET_COUNT)
            .remove(KEY_REMOTE_QUEUE_TARGET_NAMES)
            .apply()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "历史同步通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示你让谁同步，以及本机正在帮谁同步"
            }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
