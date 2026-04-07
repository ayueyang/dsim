package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 必须组件：接收新彩信的广播接收器
 * 当应用成为默认短信应用时，系统会发送 WAP_PUSH_DELIVER_ACTION
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 占位逻辑
    }
}