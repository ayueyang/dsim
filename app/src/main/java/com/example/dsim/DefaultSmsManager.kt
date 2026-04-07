package com.example.dsim

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * dSIM 权限网关：管理默认短信应用身份的请求与状态检查
 * 严格适配 Android 10+ 的 RoleManager 规范
 */
object DefaultSmsManager {

    private const val TAG = "dSIM_RoleManager"

    /**
     * 检查当前应用是否已经是系统的默认短信应用
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            } else {
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查默认短信应用状态时发生异常", e)
            false
        }
    }

    /**
     * 构建请求成为默认短信应用的 Intent
     * @return 如果需要申请则返回 Intent，如果已经是默认应用或系统不支持则返回 null
     */
    fun createRequestRoleIntent(context: Context): Intent? {
        if (isDefaultSmsApp(context)) {
            Log.d(TAG, "当前应用已经是默认短信应用，无需再次申请。")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                } else {
                    Log.e(TAG, "当前系统不支持 ROLE_SMS 角色！")
                    null
                }
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "构建请求 Intent 时发生异常", e)
            null
        }
    }
}