package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
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
            var shouldFlush = false
            try {
                shouldFlush = OutgoingSmsDispatcher.onSentResult(context.applicationContext, uuid, part, code) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DsimLog.e("dSIM_Send", "Failed to persist SMS sent callback", e)
            } finally {
                pending.finish()
            }
            // State and both outbox rows are already committed before finish(). A crash here
            // only delays the durable queue until the next connection or heartbeat.
            if (shouldFlush) SyncOutbox.requestFlush(context.applicationContext)
        }
    }
}
