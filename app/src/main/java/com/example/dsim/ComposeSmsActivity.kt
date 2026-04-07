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
        // 占位逻辑：将来可以显示一个发送短信的界面
        finish() 
    }
}