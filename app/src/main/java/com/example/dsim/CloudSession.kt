package com.example.dsim

/**
 * Credentials of the group the service is currently bound to. Owned by [MqttSyncService], read by
 * [MqttPublisher] and [MqttInboundHandler]. Blank fields mean "not configured".
 */
internal class CloudSession {
    @Volatile var broker: String = ""
    @Volatile var topic: String = ""
    @Volatile var password: String = ""

    val isConfigured: Boolean get() = topic.isNotBlank() && password.isNotBlank()

    fun config(): CloudSettingsManager.CloudConfig = CloudSettingsManager.CloudConfig(broker, topic, password)
}
