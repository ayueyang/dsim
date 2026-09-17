package com.example.dsim

import android.content.Context

object OnboardingStateStore {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_HAS_SEEN_ONBOARDING = "HAS_SEEN_ONBOARDING"
    private const val KEY_ANTI_FRAUD_ACKNOWLEDGED = "ANTI_FRAUD_ACKNOWLEDGED"

    fun hasSeenOnboarding(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SEEN_ONBOARDING, false)
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SEEN_ONBOARDING, true)
            .apply()
    }

    fun isAntiFraudAcknowledged(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANTI_FRAUD_ACKNOWLEDGED, false)
    }

    fun setAntiFraudAcknowledged(context: Context, acknowledged: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ANTI_FRAUD_ACKNOWLEDGED, acknowledged)
            .apply()
    }
}
