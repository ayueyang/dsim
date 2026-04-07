package com.example.dsim

import android.app.IntentService
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 必须组件：静默短信发送服务
 * 系统在某些场景下需要直接发送短信而无需界面
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}