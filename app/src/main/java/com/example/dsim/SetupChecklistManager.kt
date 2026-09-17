package com.example.dsim

import android.content.Context
import com.example.dsim.database.DsimDatabase

object SetupChecklistManager {
    suspend fun hasLocalSimBinding(context: Context): Boolean {
        return DsimDatabase.getDatabase(context)
            .dsimDao()
            .getActiveSimConfigs()
            .any { it.bindMode != "REMOTE_SHADOW" }
    }

    suspend fun missingItems(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!OnboardingStateStore.isAntiFraudAcknowledged(context)) {
            missing += "安全确认"
        }
        if (!CorePermissionHelper.hasAllPermissions(context)) {
            missing += "基础权限"
        }
        if (!DefaultSmsManager.isDefaultSmsApp(context)) {
            missing += "默认短信应用"
        }
        if (!hasLocalSimBinding(context)) {
            missing += "SIM 绑定"
        }
        if (!UsageModeManager.isLocalOnly(context) && !CloudSettingsManager.hasConnectionConfig(context)) {
            missing += "云端通道"
        }
        return missing
    }
}
