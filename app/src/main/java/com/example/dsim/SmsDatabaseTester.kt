package com.example.dsim

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsDatabaseTester {

    private const val TAG = "dSIM_SmsDb"
    private const val MAX_PREVIEW_COUNT = 5

    suspend fun insertFakeSms(context: Context, sender: String, body: String): String = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }

            Log.d(TAG, "Insert fake sms into system inbox: from=$sender")
            val newUri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            return@withContext if (newUri != null) {
                "存入收件箱成功: $sender"
            } else {
                "存入失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Insert fake sms failed", e)
            return@withContext "写入异常: ${e.message}"
        }
    }

    suspend fun readRecentSms(context: Context): String = withContext(Dispatchers.IO) {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC"
        val sb = StringBuilder()
        sb.append("========== 系统短信库读取 ==========\n\n")

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.count == 0) {
                    sb.append("系统短信库为空\n")
                } else {
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val subIdIdx = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                    var shown = 0
                    while (cursor.moveToNext() && shown < MAX_PREVIEW_COUNT) {
                        val address = cursor.getString(addressIdx).orEmpty()
                        val body = cursor.getString(bodyIdx).orEmpty()
                        val subId = if (subIdIdx >= 0) cursor.getInt(subIdIdx) else null
                        sb.append("[$shown] $address")
                        if (subId != null) {
                            sb.append(" | subId=$subId")
                        }
                        sb.append('\n')
                        sb.append(body).append("\n")
                        sb.append("----------------------------------------\n")
                        shown++
                    }
                }
            } ?: sb.append("查询失败\n")
        } catch (e: Exception) {
            sb.append("读取异常: ${e.message}\n")
        }
        return@withContext sb.toString()
    }

    suspend fun readAllHistoricalSms(context: Context): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("========== 系统历史短信读取 ==========\n\n")
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.count == 0) {
                    sb.append("收件箱没有任何短信\n")
                } else {
                    sb.append("成功读取 ${cursor.count} 条收件箱短信，以下展示最近 50 条：\n\n")
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

                    var shown = 0
                    while (cursor.moveToNext() && shown < 50) {
                        val address = cursor.getString(addressIdx).orEmpty()
                        val body = cursor.getString(bodyIdx).orEmpty()
                        val timestamp = cursor.getLong(dateIdx)
                        val isRead = cursor.getInt(readIdx) == 1
                        val dateStr = dateFormat.format(Date(timestamp))
                        val readStatus = if (isRead) "[已读]" else "[未读]"
                        sb.append("发件人: $address $readStatus\n")
                        sb.append("时间: $dateStr\n")
                        sb.append("内容: $body\n")
                        sb.append("-----------------------------------\n")
                        shown++
                    }
                }
            } ?: sb.append("查询失败\n")
        } catch (e: Exception) {
            sb.append("读取崩溃: ${e.message}\n")
        }
        return@withContext sb.toString()
    }

    suspend fun readSystemAndAppDbSummary(context: Context): String = withContext(Dispatchers.IO) {
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.append("========== 双库只读测试 ==========\n\n")

        try {
            val systemPreview = mutableListOf<String>()
            val systemUri = Telephony.Sms.CONTENT_URI
            val systemProjection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.SUBSCRIPTION_ID
            )

            context.contentResolver.query(
                systemUri,
                systemProjection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val total = cursor.count
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val subIdIdx = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                var shown = 0
                while (cursor.moveToNext() && shown < MAX_PREVIEW_COUNT) {
                    val address = cursor.getString(addressIdx).orEmpty().ifBlank { "(空号码)" }
                    val body = shorten(cursor.getString(bodyIdx))
                    val dateText = dateFormat.format(Date(cursor.getLong(dateIdx)))
                    val typeLabel = smsTypeLabel(cursor.getInt(typeIdx))
                    val subIdText = if (subIdIdx >= 0) {
                        "subId=${cursor.getInt(subIdIdx)}"
                    } else {
                        "subId=?"
                    }
                    systemPreview += "${shown + 1}. $dateText | $address | $typeLabel | $subIdText | $body"
                    shown++
                }

                sb.append("系统库: 可读\n")
                sb.append("总数: $total\n")
                if (systemPreview.isEmpty()) {
                    sb.append("最近记录: 无\n\n")
                } else {
                    sb.append("最近 ${systemPreview.size} 条:\n")
                    sb.append(systemPreview.joinToString("\n"))
                    sb.append("\n\n")
                }
            } ?: run {
                sb.append("系统库: 查询返回空游标\n\n")
            }
        } catch (e: Exception) {
            sb.append("系统库: 读取失败\n")
            sb.append("原因: ${e.message}\n\n")
        }

        try {
            val appTotal = dao.countSmsMessages()
            val appPreview = dao.getLatestSmsMessages(MAX_PREVIEW_COUNT)

            sb.append("App库(sms_messages): 可读\n")
            sb.append("总数: $appTotal\n")
            if (appPreview.isEmpty()) {
                sb.append("最近记录: 无\n")
            } else {
                sb.append("最近 ${appPreview.size} 条:\n")
                sb.append(appPreview.joinToString("\n") { formatAppMessagePreview(it, dateFormat) })
                sb.append('\n')
            }
        } catch (e: Exception) {
            sb.append("App库(sms_messages): 读取失败\n")
            sb.append("原因: ${e.message}\n")
        }

        return@withContext sb.toString()
    }

    private fun formatAppMessagePreview(message: SmsMessage, dateFormat: SimpleDateFormat): String {
        val address = message.address.ifBlank { "(空号码)" }
        val mapping = message.mappingKey.ifBlank { "(无mappingKey)" }
        val body = shorten(message.body)
        val dateText = dateFormat.format(Date(message.timestamp))
        return "${message.id}. $dateText | $address | simId=${message.simId} | $mapping | $body"
    }

    private fun shorten(value: String?): String {
        val safe = value.orEmpty().replace('\n', ' ').trim()
        return if (safe.length <= 28) safe else safe.take(28) + "..."
    }

    private fun smsTypeLabel(type: Int): String {
        return when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "收"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "发"
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "草稿"
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "发件箱"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "失败"
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "队列"
            else -> "type=$type"
        }
    }
}
