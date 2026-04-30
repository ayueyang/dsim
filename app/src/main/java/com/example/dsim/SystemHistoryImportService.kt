package com.example.dsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class HistoryImportUiState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val stage: String = "空闲",
    val systemCount: Int = 0,
    val appCount: Int = 0,
    val scannedCount: Int = 0,
    val importedCount: Int = 0,
    val syncedCount: Int = 0,
    val skippedCount: Int = 0,
    val currentAddress: String = "",
    val detailMessage: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun hasSnapshot(): Boolean {
        return isRunning ||
            isPaused ||
            scannedCount > 0 ||
            importedCount > 0 ||
            syncedCount > 0 ||
            skippedCount > 0 ||
            detailMessage.isNotBlank()
    }
}

class SystemHistoryImportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pauseRequested = AtomicBoolean(false)
    private var importJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "dsim_history_import_queue"
        private const val NOTIFICATION_ID = HistoryQueueNotificationHelper.EXECUTION_NOTIFICATION_ID

        const val ACTION_START_IMPORT = "com.example.dsim.START_HISTORY_IMPORT"
        const val ACTION_PAUSE_IMPORT = "com.example.dsim.PAUSE_HISTORY_IMPORT"
        const val EXTRA_OPEN_QUEUE = "OPEN_HISTORY_QUEUE"

        val stateFlow = MutableStateFlow(HistoryImportUiState())

        fun startImport(context: Context) {
            val intent = Intent(context, SystemHistoryImportService::class.java).apply {
                action = ACTION_START_IMPORT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseImport(context: Context) {
            val intent = Intent(context, SystemHistoryImportService::class.java).apply {
                action = ACTION_PAUSE_IMPORT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun clearSnapshot() {
            if (!stateFlow.value.isRunning) {
                stateFlow.value = HistoryImportUiState()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_IMPORT -> {
                if (stateFlow.value.isRunning) {
                    pauseRequested.set(true)
                    val pausedPendingState = stateFlow.value.copy(
                        stage = "已请求暂停",
                        detailMessage = "正在收尾当前这条，完成后会停在断点位置。",
                        updatedAt = System.currentTimeMillis()
                    )
                    stateFlow.value = pausedPendingState
                    updateNotification(pausedPendingState)
                }
                return START_NOT_STICKY
            }

            ACTION_START_IMPORT -> {
                if (importJob?.isActive == true) {
                    updateNotification(stateFlow.value)
                    return START_STICKY
                }

                pauseRequested.set(false)
                val startingState = HistoryImportUiState(
                    isRunning = true,
                    isPaused = false,
                    stage = "准备开始",
                    detailMessage = "后台队列已启动，正在读取系统短信。",
                    startedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                stateFlow.value = startingState
                serviceScope.launch {
                    HistorySyncQueueManager.updateFromImportState(this@SystemHistoryImportService, startingState)
                }
                startForeground(NOTIFICATION_ID, buildNotification(startingState))
                importJob = serviceScope.launch {
                    runImport()
                }
                return START_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runImport() {
        try {
            val result = SystemSmsHistoryImporter.importQueuedHistory(
                context = this,
                progressListener = object : SystemSmsHistoryImporter.ProgressListener {
                    override fun onProgress(progress: SystemSmsHistoryImporter.ImportProgress) {
                        val updatedState = HistoryImportUiState(
                            isRunning = true,
                            isPaused = false,
                            stage = progress.stage,
                            systemCount = progress.systemCount,
                            appCount = progress.appCount,
                            scannedCount = progress.scannedCount,
                            importedCount = progress.importedCount,
                            syncedCount = progress.syncedCount,
                            skippedCount = progress.skippedCount,
                            currentAddress = progress.currentAddress,
                            detailMessage = buildDetailMessage(progress),
                            startedAt = progress.startedAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        stateFlow.value = updatedState
                        serviceScope.launch {
                            HistorySyncQueueManager.updateFromImportState(this@SystemHistoryImportService, updatedState)
                        }
                        updateNotification(updatedState)
                    }

                    override fun isPauseRequested(): Boolean = pauseRequested.get()
                }
            )

            val finalState = HistoryImportUiState(
                isRunning = false,
                isPaused = result.wasPaused,
                stage = when {
                    result.wasPaused -> "队列已暂停"
                    result.completedAll -> "历史同步完成"
                    else -> "队列已暂停"
                },
                systemCount = stateFlow.value.systemCount,
                appCount = stateFlow.value.appCount,
                scannedCount = result.scannedCount,
                importedCount = result.importedCount,
                syncedCount = result.syncedCount,
                skippedCount = result.skippedCount,
                currentAddress = stateFlow.value.currentAddress,
                detailMessage = result.message,
                startedAt = stateFlow.value.startedAt,
                updatedAt = System.currentTimeMillis()
            )
            stateFlow.value = finalState
            HistorySyncQueueManager.updateFromImportState(this@SystemHistoryImportService, finalState)
            publishFinalNotification(finalState)
        } catch (e: Exception) {
            val failedState = stateFlow.value.copy(
                isRunning = false,
                isPaused = false,
                stage = "历史同步失败",
                detailMessage = "后台队列异常停止：${e.message ?: "unknown error"}",
                updatedAt = System.currentTimeMillis()
            )
            stateFlow.value = failedState
            HistorySyncQueueManager.updateFromImportState(this@SystemHistoryImportService, failedState)
            publishFinalNotification(failedState)
        } finally {
            pauseRequested.set(false)
            importJob = null
            stopSelf()
        }
    }

    private fun buildDetailMessage(progress: SystemSmsHistoryImporter.ImportProgress): String {
        val lines = mutableListOf<String>()
        lines += "本次已扫描 ${progress.scannedCount} / ${progress.systemCount} 条"
        lines += "新增 ${progress.importedCount} 条，云端确认 ${progress.syncedCount} 条，跳过 ${progress.skippedCount} 条"
        lines += "软件库当前约 ${progress.appCount} 条"
        lines += if (progress.cloudConnected) {
            if (progress.waitingForAck) {
                "云端队列：已发出，正在等待对端 ACK"
            } else {
                "云端队列：在线串行同步"
            }
        } else {
            "云端队列：当前未连接，只写入本机软件库"
        }
        if (progress.currentAddress.isNotBlank()) {
            lines += "当前处理：${progress.currentAddress}"
        }
        return lines.joinToString("\n")
    }

    private fun buildNotification(state: HistoryImportUiState): Notification {
        return HistoryQueueNotificationHelper.buildExecutionNotification(
            context = this,
            state = state,
            snapshot = HistorySyncQueueManager.getLocalQueueSnapshot(this)
        )
    }

    private fun updateNotification(state: HistoryImportUiState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun publishFinalNotification(state: HistoryImportUiState) {
        stopForeground(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
}
