package com.example.dsim

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 必须组件：短信编辑界面
 * 当其他应用调用 SMS 相关的 Intent 时，系统会尝试打开此 Activity
 */
class ComposeSmsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DsimLog.w("dSIM_Placeholder", "占位实现，未处理短信编辑请求")
        finish()
    }
}
