package com.example.dsim

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_OTP) {
            return
        }

        val code = intent.getStringExtra(EXTRA_OTP_CODE)?.trim().orEmpty()
        if (code.isBlank()) {
            return
        }

        if (isDeviceLocked(context)) {
            Toast.makeText(context, "请先解锁后复制验证码", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("验证码", code).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboardManager.setPrimaryClip(clip)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            OtpCopyPreferenceStore.shouldShowCopyToast(context)
        ) {
            Toast.makeText(context, "已复制验证码", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
        } else {
            keyguardManager.isKeyguardLocked
        }
    }

    companion object {
        const val ACTION_COPY_OTP = "com.example.dsim.action.COPY_OTP"
        const val EXTRA_OTP_CODE = "com.example.dsim.extra.OTP_CODE"
        const val EXTRA_SMS_UUID = "com.example.dsim.extra.SMS_UUID"
        const val EXTRA_SMS_ADDRESS = "com.example.dsim.extra.SMS_ADDRESS"
        const val EXTRA_MAPPING_KEY = "com.example.dsim.extra.MAPPING_KEY"
    }
}
