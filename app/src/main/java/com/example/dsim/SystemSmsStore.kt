package com.example.dsim

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log

object SystemSmsStore {

    private const val TAG = "dSIM_SystemSmsStore"
    private const val DEDUPE_WINDOW_MS = 2 * 60 * 1000L

    fun insertIncomingIfNeeded(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?
    ) {
        insertIfNeeded(
            context = context,
            targetUri = Telephony.Sms.Inbox.CONTENT_URI,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            address = address,
            body = body,
            timestamp = timestamp,
            subscriptionId = subscriptionId,
            isRead = false,
            isSeen = false
        )
    }

    fun insertSentIfNeeded(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?
    ) {
        insertIfNeeded(
            context = context,
            targetUri = Telephony.Sms.Sent.CONTENT_URI,
            type = Telephony.Sms.MESSAGE_TYPE_SENT,
            address = address,
            body = body,
            timestamp = timestamp,
            subscriptionId = subscriptionId,
            isRead = true,
            isSeen = true
        )
    }

    private fun insertIfNeeded(
        context: Context,
        targetUri: android.net.Uri,
        type: Int,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?,
        isRead: Boolean,
        isSeen: Boolean
    ) {
        if (!DefaultSmsManager.isDefaultSmsApp(context)) {
            Log.d(TAG, "Skip system SMS insert because app is not the default SMS app")
            return
        }

        try {
            if (hasSimilarMessage(context, address, body, type, timestamp, subscriptionId)) {
                Log.d(TAG, "Skip duplicate system SMS insert: address=$address type=$type subId=$subscriptionId")
                return
            }

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.TYPE, type)
                put(Telephony.Sms.READ, if (isRead) 1 else 0)
                put(Telephony.Sms.SEEN, if (isSeen) 1 else 0)
                subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
            }

            val insertedUri = context.contentResolver.insert(targetUri, values)
            Log.d(TAG, "Inserted system SMS row: uri=$insertedUri address=$address type=$type subId=$subscriptionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert SMS into system provider", e)
        }
    }

    private fun hasSimilarMessage(
        context: Context,
        address: String,
        body: String,
        type: Int,
        timestamp: Long,
        subscriptionId: Int?
    ): Boolean {
        val selectionParts = mutableListOf(
            "${Telephony.Sms.ADDRESS} = ?",
            "${Telephony.Sms.BODY} = ?",
            "${Telephony.Sms.TYPE} = ?",
            "${Telephony.Sms.DATE} BETWEEN ? AND ?"
        )
        val args = mutableListOf(
            address,
            body,
            type.toString(),
            (timestamp - DEDUPE_WINDOW_MS).toString(),
            (timestamp + DEDUPE_WINDOW_MS).toString()
        )

        if (subscriptionId != null) {
            selectionParts += "${Telephony.Sms.SUBSCRIPTION_ID} = ?"
            args += subscriptionId.toString()
        }

        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }
}
