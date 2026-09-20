package com.example.dsim

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderComponentTest {
    @Test fun allThreePlaceholdersLogTheirUnimplementedEntryPoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        shell("logcat -c")
        MmsReceiver().onReceive(context, Intent("com.example.dsim.UNSUPPORTED_MMS"))
        assertTrue("unsupported action must be ignored", !shell("logcat -d -s dSIM_Placeholder:W '*:S'")
            .contains("未处理 MMS 接收"))
        MmsReceiver().onReceive(context, Intent("android.provider.Telephony.WAP_PUSH_DELIVER"))
        ActivityScenario.launch(ComposeSmsActivity::class.java).use { instrumentation.waitForIdleSync() }
        // Exercise the service callback, not an actual SMS send; no recipient/body is supplied.
        HeadlessSmsSendService().onStartCommand(Intent("android.intent.action.RESPOND_VIA_MESSAGE"), 0, 1)
        val logs = shell("logcat -d -s dSIM_Placeholder:W '*:S'")
        for (message in listOf("占位实现，未处理 MMS 接收", "占位实现，未处理短信编辑请求", "占位实现，未处理静默短信发送请求")) {
            assertTrue("missing placeholder log: $message", logs.contains(message))
        }
    }
}
