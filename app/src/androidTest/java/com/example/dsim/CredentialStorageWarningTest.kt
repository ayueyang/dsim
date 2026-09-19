package com.example.dsim

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T1.5 acceptance: when [CredentialVault.seal] refuses, saving must keep working (usability) but
 * the settings screen has to say so persistently instead of degrading silently.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStorageWarningTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
    private val warningText = "口令当前以明文存储（本机 Keystore 不可用）"

    @Test fun keystoreRefusalIsVisibleAndSealedStorageIsNot() {
        val snapshot = HashMap<String, Any?>(prefs.all)
        try {
            // Test double: the Keystore refuses to seal anything.
            CredentialVault.sealOverride = { null }
            CloudSettingsManager.saveConfig(context, "tcp://127.0.0.1:1883", "dsim/test/t15", "t15-password")

            assertEquals("saving must stay possible", "t15-password", CloudSettingsManager.getConfig(context).password)
            assertEquals("t15-password", prefs.getString("PASSWORD", null))
            assertNull("no sealed value can exist", prefs.getString("PASSWORD_ENC", null))
            assertTrue(CloudSettingsManager.isPasswordPlaintextFallback(context))
            assertFalse(CloudSettingsManager.isPasswordSealed(context))
            assertEquals(warningText, visibleWarningText())

            // Normal device: sealing works, the password is sealed and no warning is shown.
            CredentialVault.sealOverride = null
            CloudSettingsManager.saveConfig(context, "tcp://127.0.0.1:1883", "dsim/test/t15", "t15-password")
            assertTrue("sealed storage expected", CloudSettingsManager.isPasswordSealed(context))
            assertFalse(CloudSettingsManager.isPasswordPlaintextFallback(context))
            assertEquals("t15-password", CloudSettingsManager.getConfig(context).password)
            assertNull("warning must be hidden on a normal device", visibleWarningText())
        } finally {
            CredentialVault.sealOverride = null
            context.stopService(Intent(context, MqttSyncService::class.java))
            restore(snapshot)
        }
    }

    /** Warning text when visible, null when hidden. Read on the UI thread of a real SettingsActivity. */
    private fun visibleWarningText(): String? {
        var text: String? = null
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val warning = activity.findViewById<TextView>(R.id.tvCredentialStorageWarning)
                text = if (warning.visibility == View.VISIBLE) warning.text.toString() else null
            }
        }
        return text
    }

    private fun restore(snapshot: Map<String, Any?>) {
        val editor = prefs.edit().clear()
        snapshot.forEach { (key, value) ->
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
