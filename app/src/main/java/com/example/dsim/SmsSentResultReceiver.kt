package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Explicit PendingIntent target; survives Service/process recreation, not exported. */
class SmsSentResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OutgoingSmsDispatcher.ACTION_SENT) return
        val uuid = intent.getStringExtra("uuid")?.takeIf { it.isNotBlank() } ?: return
        val part = intent.getIntExtra("part", -1)
        val code = resultCode
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var outcome: com.example.dsim.database.SendCommandRecord? = null
            try {
                outcome = OutgoingSmsDispatcher.onSentResult(context.applicationContext, uuid, part, code)
            } catch (e: Exception) {
                Log.e("dSIM_Send", "Failed to persist SMS sent callback", e)
            } finally {
                pending.finish()
            }
            // Broker I/O must not hold BroadcastReceiver's time budget. The result is durable;
            // publication is best-effort and the same UUID can be queried after reconnect.
            outcome?.let { OutgoingSmsDispatcher.publishOutcome(context.applicationContext, it) }
        }
    }
}
