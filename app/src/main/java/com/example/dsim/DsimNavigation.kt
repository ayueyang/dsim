package com.example.dsim

import android.app.Activity
import android.content.Intent

object DsimNavigation {
    fun backToInboxOrFinish(activity: Activity) {
        if (!activity.isTaskRoot) {
            activity.finish()
            return
        }

        activity.startActivity(
            Intent(activity, SmsListActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        activity.finish()
    }
}
