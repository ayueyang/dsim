package com.example.dsim

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    @Test fun rejectedServiceStartDoesNotCrashAndLockedBootIsIgnored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val saved = HashMap<String, Any?>(prefs.all)
        try {
            CloudSettingsManager.saveConfig(context, "tcp://127.0.0.1:1", "dsim/test/boot", UUID.randomUUID().toString())
            prefs.edit().putBoolean("ANTI_FRAUD_ACKNOWLEDGED", true)
                .putBoolean("AUTO_CONNECT", true).putString("USAGE_MODE", UsageMode.BIDIRECTIONAL_SYNC.name).commit()
            for (rejection in listOf(IllegalStateException("test background restriction"), SecurityException("test permission restriction"))) {
                var attempts = 0
                val rejectingContext = object : ContextWrapper(context) {
                    override fun startService(service: Intent): ComponentName? {
                        attempts++
                        assertEquals(MqttSyncService.ACTION_CONNECT, service.action)
                        throw rejection
                    }
                    override fun startForegroundService(service: Intent): ComponentName? = startService(service)
                }
                BootReceiver().onReceive(rejectingContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
                assertEquals("not directBootAware: locked boot must be ignored", 0, attempts)
                BootReceiver().onReceive(rejectingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
                assertEquals("configured boot must attempt once and absorb rejection", 1, attempts)
            }
            android.util.Log.i("dSIM_BatchA", "BOOT_REJECTION IllegalStateException+SecurityException contained; LOCKED_BOOT ignored")
        } finally {
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                }
            }
            editor.commit()
        }
    }
}
