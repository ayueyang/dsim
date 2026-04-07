package com.example.dsim

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * dSIM 底层数据库测试器
 */
object SmsDatabaseTester {

    private const val TAG = "dSIM_SmsDb"

    /**
     * 向系统收件箱插入一条短信 (支持动态数据)
     */
    suspend fun insertFakeSms(context: Context, sender: String, body: String): String = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender) // 动态发件人
                put(Telephony.Sms.BODY, body)      // 动态短信内容
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 0) // 0表示未读
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }

            Log.d(TAG, "开始向系统数据库注入短信: From=$sender")
            val newUri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            return@withContext if (newUri != null) "存入收件箱成功: $sender" else "存入失败"
        } catch (e: Exception) {
            Log.e(TAG, "写入短信时发生异常", e)
            return@withContext "写入异常: ${e.message}"
        }
    }

    /**
     * 读取系统短信数据库中最新的 5 条短信
     */
    suspend fun readRecentSms(context: Context): String = withContext(Dispatchers.IO) {
        val uri = Telephony.Sms.CONTENT_URI 
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT 5"
        val sb = StringBuilder()
        sb.append("========== dSIM 短信数据库读取报告 ==========\n\n")

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.count == 0) {
                    sb.append("系统短信数据库目前为空！\n")
                } else {
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val subIdIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)

                    while (cursor.moveToNext()) {
                        val address = cursor.getString(addressIdx)
                        val body = cursor.getString(bodyIdx)
                        val subId = cursor.getInt(subIdIdx)
                        sb.append("【来自】: $address (接收卡 SubId: $subId)\n")
                        sb.append("【内容】: $body\n")
                        sb.append("----------------------------------------\n")
                    }
                }
            } ?: sb.append("查询失败\n")
        } catch (e: Exception) {
            sb.append("读取异常：${e.message}\n")
        }
        return@withContext sb.toString()
    }

    /**
     * 深度读取本机历史短信
     */
    suspend fun readAllHistoricalSms(context: Context): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("========== dSIM 历史短信全量读取 ==========\n\n")
        val uri = Telephony.Sms.Inbox.CONTENT_URI 
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT 50"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.count == 0) {
                    sb.append("收件箱没有任何短信！\n")
                } else {
                    sb.append("成功拉取到 ${cursor.count} 条历史短信：\n\n")
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

                    while (cursor.moveToNext()) {
                        val address = cursor.getString(addressIdx)
                        val body = cursor.getString(bodyIdx)
                        val timestamp = cursor.getLong(dateIdx)
                        val isRead = cursor.getInt(readIdx) == 1
                        val dateStr = dateFormat.format(java.util.Date(timestamp))
                        val readStatus = if (isRead) "[已读]" else "[未读]"
                        sb.append("发件人: $address $readStatus\n时间: $dateStr\n内容: $body\n-----------------------------------\n")
                    }
                }
            } ?: sb.append("查询失败\n")
        } catch (e: Exception) {
            sb.append("读取崩溃: ${e.message}\n")
        }
        return@withContext sb.toString()
    }
}