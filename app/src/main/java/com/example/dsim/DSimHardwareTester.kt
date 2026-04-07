package com.example.dsim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.regex.Pattern

/**
 * dSIM 探路者：全方位硬件信息与权限测试类
 * 专为初次验证设备可行性设计，将所有探测结果汇总为可视化的文本报告。
 */
object DSimHardwareTester {

    private const val TAG = "dSIM_Tester"

    /**
     * 主动触发 Root 权限申请
     * @return 是否成功获得 Root
     */
    suspend fun checkAndRequestRoot(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root_ok"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            result == "root_ok"
        } catch (e: Exception) {
            Log.e(TAG, "Root 请求异常: ${e.message}")
            false
        }
    }

    /**
     * 运行完整测试并生成报告 (在 IO 线程执行，不卡顿 UI)
     */
    suspend fun runFullTest(context: Context): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        report.append("========== dSIM 硬件探测报告 ==========\n\n")

        // 1. 测试设备唯一标识
        report.append("【阶段一：设备标识提取】\n")
        val deviceId = getDeviceId(context)
        report.append("获取结果: $deviceId\n\n")

        // 2. 测试官方 API 获取 SIM 卡 (SubId)
        report.append("【阶段二：官方 API 探测 (需 READ_PHONE_STATE & READ_PHONE_NUMBERS)】\n")
        val standardSimInfo = getStandardSimInfo(context)
        report.append(standardSimInfo).append("\n")

        // 3. 测试 Root 权限获取底层 ICCID
        report.append("【阶段三：Root 底层数据库探测 (提取 ICCID)】\n")
        val rootSimInfo = getRootSimInfo()
        report.append(rootSimInfo).append("\n")

        report.append("================ 探测结束 ================")
        val finalReport = report.toString()
        Log.d(TAG, finalReport) // 同时打印到 Logcat
        
        return@withContext finalReport
    }

    private fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId.isNullOrBlank() || androidId.lowercase() == "9774d56d682e549c") {
                "Android_ID无效，生成备用 UUID: ${UUID.randomUUID()}"
            } else {
                "Android_ID: $androidId"
            }
        } catch (e: Exception) {
            "获取失败: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getStandardSimInfo(context: Context): String {
        val hasPhoneState = ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneNumbers = ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneState) {
            return "错误：未授予 READ_PHONE_STATE 权限！"
        }

        val sb = StringBuilder()
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeCards = subscriptionManager.activeSubscriptionInfoList
            
            if (activeCards.isNullOrEmpty()) {
                sb.append("未检测到任何激活的 SIM 卡。")
            } else {
                sb.append("共检测到 ${activeCards.size} 张卡：\n")
                for (info in activeCards) {
                    val phoneNumber = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (hasPhoneNumbers) {
                                subscriptionManager.getPhoneNumber(info.subscriptionId)
                            } else {
                                "缺少权限(READ_PHONE_NUMBERS)"
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            info.number ?: "未烧录"
                        }
                    } catch (e: Exception) {
                        "获取失败: ${e.message}"
                    }
                    
                    val displayNum = if (phoneNumber.isNullOrBlank()) "未烧录" else phoneNumber
                    sb.append(" -> 卡槽 ${info.simSlotIndex}: SubId=${info.subscriptionId}, 运营商=${info.carrierName}, 号码=$displayNum\n")
                }
            }
        } catch (e: Exception) {
            sb.append("官方 API 调用崩溃: ${e.message}")
        }
        return sb.toString()
    }

    private fun getRootSimInfo(): String {
        val sb = StringBuilder()
        var process: Process? = null
        try {
            // 第一步：先测试能不能拿到 su 权限
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root_ok"))
            val rootCheck = BufferedReader(InputStreamReader(process.inputStream)).readLine()
            if (rootCheck != "root_ok") {
                return "设备未 Root 或 Root 权限被拒绝。"
            }
            sb.append("Root 权限校验通过！\n")

            // 第二步：执行底层数据库查询
            val command = "content query --uri content://telephony/siminfo --projection _id:icc_id:display_name"
            val queryProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(queryProcess.inputStream))
            
            var line: String?
            val pattern = Pattern.compile("_id=(.*?),\\s*icc_id=(.*?),\\s*display_name=(.*)")
            var foundRecord = false

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) continue
                val matcher = pattern.matcher(line!!)
                if (matcher.find()) {
                    foundRecord = true
                    val subId = matcher.group(1)?.trim() ?: "未知"
                    val iccId = matcher.group(2)?.trim() ?: "未知"
                    val name = matcher.group(3)?.trim() ?: "未知"
                    sb.append(" -> 强读成功: SubId=$subId, ICCID=$iccId, 名称=$name\n")
                }
            }
            
            if (!foundRecord) {
                sb.append("查询成功，但 telephony 数据库为空或解析失败。")
            }
            queryProcess.waitFor()
            
        } catch (e: Exception) {
            sb.append("Root 执行过程中发生异常: ${e.message}")
        } finally {
            process?.destroy()
        }
        return sb.toString()
    }
}