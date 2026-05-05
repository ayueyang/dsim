package com.example.dsim

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.dsim.database.SmsMessage

object NotificationUtils {
    private const val CHANNEL_LOUD = "dsim_loud_v1"
    private const val CHANNEL_SILENT = "dsim_silent_v1"

    private fun ensureChannelsExist(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            notificationManager.deleteNotificationChannel("dsim_new_message_channel")
            
            if (notificationManager.getNotificationChannel(CHANNEL_LOUD) == null) {
                val loudChannel = NotificationChannel(CHANNEL_LOUD, "极客高优新消息 (响铃)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "带声音和横幅的新短信提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                }
                notificationManager.createNotificationChannel(loudChannel)
            }
            
            if (notificationManager.getNotificationChannel(CHANNEL_SILENT) == null) {
                val silentChannel = NotificationChannel(CHANNEL_SILENT, "极客静默新消息 (静音)", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "无声音无震动的静默提醒"
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(silentChannel)
            }
        }
    }

    fun createNotificationChannel(context: Context) {
        ensureChannelsExist(context)
    }

    fun showNewMessageNotification(context: Context, sms: SmsMessage, receivingPhone: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        ensureChannelsExist(context)

        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val isMuted = prefs.getBoolean("IS_MUTED", false)
        
        val targetChannel = if (isMuted) CHANNEL_SILENT else CHANNEL_LOUD
        val targetPriority = if (isMuted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX

        PrivacyModeManager.rememberOwnPhone(context, receivingPhone)
        val profile = ConversationProfileStore.load(context, sms.address)
        val senderAddress = PrivacyModeManager.displayConversationAddress(context, sms.address)
            .ifBlank { sms.address }
        val title = profile.remark.trim().takeIf { it.isNotBlank() }
            ?.let { "$it $senderAddress" }
            ?: senderAddress
        val previewBody = PrivacyModeManager.displaySmsNotificationBody(context, sms.body)
        val receivingLine = receivingPhone
            ?.takeIf { it.isNotBlank() }
            ?.let { "接收卡 ${PrivacyModeManager.displayOwnPhone(context, it)} · " }
            .orEmpty()
        val previewText = receivingLine + previewBody
        val intent = Intent(context, SmsChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("CHAT_ADDRESS", sms.address)
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 
            sms.address.hashCode(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, targetChannel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
            .setPriority(targetPriority)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        buildOtpCopyAction(context, sms)?.let { builder.addAction(it) }

        if (!isMuted) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            builder.setVibrate(longArrayOf(0, 250, 250, 250))
        }

        with(NotificationManagerCompat.from(context)) {
            notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
        }
    }

    private fun buildOtpCopyAction(context: Context, sms: SmsMessage): NotificationCompat.Action? {
        val otpItem = OtpConversationUtils.toOtpItem(
            context = context,
            sms = sms,
            settings = OtpRulesStore.loadSettings(context),
            override = OtpRulesStore.getOverride(context, sms.uuid)
        ) ?: return null

        val copyIntent = Intent(context, OtpCopyReceiver::class.java).apply {
            action = OtpCopyReceiver.ACTION_COPY_OTP
            putExtra(OtpCopyReceiver.EXTRA_OTP_CODE, otpItem.code)
            putExtra(OtpCopyReceiver.EXTRA_SMS_UUID, sms.uuid)
            putExtra(OtpCopyReceiver.EXTRA_SMS_ADDRESS, sms.address)
            putExtra(OtpCopyReceiver.EXTRA_MAPPING_KEY, sms.mappingKey)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            context,
            sms.uuid.hashCode(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_save,
            "复制验证码",
            copyPendingIntent
        )
            .setAuthenticationRequired(true)
            .build()
    }
}
