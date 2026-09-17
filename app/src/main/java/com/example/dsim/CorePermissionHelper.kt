package com.example.dsim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object CorePermissionHelper {
    fun requiredPermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun missingPermissions(context: Context): List<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAllPermissions(context: Context): Boolean = missingPermissions(context).isEmpty()

    fun missingPermissionLabels(context: Context): List<String> {
        return missingPermissions(context).map { labelForPermission(it) }
    }

    fun grantedSummary(context: Context): String {
        val total = requiredPermissions().size
        val missing = missingPermissions(context).size
        val granted = total - missing
        return "已授权 $granted / $total 项"
    }

    private fun labelForPermission(permission: String): String {
        return when (permission) {
            Manifest.permission.READ_SMS -> "读取短信"
            Manifest.permission.RECEIVE_SMS -> "接收短信"
            Manifest.permission.SEND_SMS -> "发送短信"
            Manifest.permission.READ_PHONE_STATE -> "读取电话状态"
            Manifest.permission.READ_PHONE_NUMBERS -> "读取本机号码"
            Manifest.permission.POST_NOTIFICATIONS -> "通知"
            else -> permission.substringAfterLast('.')
        }
    }
}
