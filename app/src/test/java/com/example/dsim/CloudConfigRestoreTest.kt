package com.example.dsim

import org.junit.Assert.*
import org.junit.Test

class CloudConfigRestoreTest {
    private val saved = CloudSettingsManager.CloudConfig("ssl://private.example:8883", "private/topic", "test-only")
    @Test fun nullIntentRestoresPrivateBrokerAlongWithTopicAndPassword() {
        assertEquals(saved, CloudSettingsManager.resolveConfig(saved, null, null, null))
    }
    @Test fun topicOverrideDoesNotResetBroker() {
        assertEquals(saved.copy(topic = "new"), CloudSettingsManager.resolveConfig(saved, null, "new", null))
    }
    @Test fun explicitConfigurationOverridesSavedValues() {
        assertEquals(CloudSettingsManager.CloudConfig("tcp://other:1883", "other", "other-secret"),
            CloudSettingsManager.resolveConfig(saved, "tcp://other:1883", "other", "other-secret"))
    }
}
