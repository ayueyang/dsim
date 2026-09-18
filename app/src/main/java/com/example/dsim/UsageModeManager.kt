package com.example.dsim

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class UsageMode {
    BIDIRECTIONAL_SYNC,
    RECEIVE_ONLY,
    FORWARD_ONLY,
    LOCAL_ONLY
}

object UsageModeManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_USAGE_MODE = "USAGE_MODE"

    fun getMode(context: Context): UsageMode {
        val rawValue = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USAGE_MODE, null)
            ?.trim()
            .orEmpty()

        return runCatching { UsageMode.valueOf(rawValue) }
            .getOrElse { defaultMode(context) }
    }

    fun setMode(context: Context, mode: UsageMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USAGE_MODE, mode.name)
            .apply()
    }

    fun applyMode(context: Context, mode: UsageMode) {
        setMode(context, mode)
        when (mode) {
            UsageMode.LOCAL_ONLY -> {
                if (MqttSyncService.hasClient()) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, MqttSyncService::class.java).apply {
                            action = MqttSyncService.ACTION_APPLY_LOCAL_MODE
                        }
                    )
                }
            }

            else -> {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MqttSyncService::class.java).apply {
                        action = MqttSyncService.ACTION_INIT_DAEMON
                    }
                )
            }
        }
    }

    fun isLocalOnly(context: Context): Boolean = getMode(context) == UsageMode.LOCAL_ONLY

    fun canUseCloud(context: Context): Boolean = !isLocalOnly(context)

    fun canUploadIncomingSms(context: Context): Boolean {
        return when (getMode(context)) {
            UsageMode.BIDIRECTIONAL_SYNC,
            UsageMode.FORWARD_ONLY -> true
            else -> false
        }
    }

    fun canReceiveCloudSms(context: Context): Boolean {
        return when (getMode(context)) {
            UsageMode.BIDIRECTIONAL_SYNC,
            UsageMode.RECEIVE_ONLY -> true
            else -> false
        }
    }

    fun displayName(mode: UsageMode): String {
        return when (mode) {
            UsageMode.BIDIRECTIONAL_SYNC -> "双向同步"
            UsageMode.RECEIVE_ONLY -> "只接收"
            UsageMode.FORWARD_ONLY -> "只转发"
            UsageMode.LOCAL_ONLY -> "本地模式"
        }
    }

    fun description(mode: UsageMode): String {
        return when (mode) {
            UsageMode.BIDIRECTIONAL_SYNC -> "接收云端同步短信和设备状态，同时上传本机收到的新短信。"
            UsageMode.RECEIVE_ONLY -> "接收云端同步短信和设备状态，但不上传本机收到的新短信。"
            UsageMode.FORWARD_ONLY -> "上传本机收到的新短信，但不接收云端同步短信落库和通知。"
            UsageMode.LOCAL_ONLY -> "关闭全部云端入口，只保留本地短信基础功能。"
        }
    }

    private fun defaultMode(context: Context): UsageMode {
        return if (CloudSettingsManager.hasConnectionConfig(context)) {
            UsageMode.BIDIRECTIONAL_SYNC
        } else {
            UsageMode.LOCAL_ONLY
        }
    }
}
