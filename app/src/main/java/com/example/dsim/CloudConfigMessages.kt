package com.example.dsim

/**
 * User-facing text for cloud configuration problems. Kept next to the validation rather than
 * duplicated in every screen that saves a broker/topic pair (SettingsActivity, OnboardingActivity).
 */
object CloudConfigMessages {

    fun topicError(reason: CloudSettingsManager.TopicValidation.Reason): String = when (reason) {
        CloudSettingsManager.TopicValidation.Reason.EMPTY ->
            "请输入房间号（MQTT Topic）"
        CloudSettingsManager.TopicValidation.Reason.WILDCARD ->
            "房间号不能包含通配符 + 或 #，否则会订阅到无关设备的消息"
        CloudSettingsManager.TopicValidation.Reason.WHITESPACE ->
            "房间号不能包含空格"
        CloudSettingsManager.TopicValidation.Reason.CONTROL_CHARACTER ->
            "房间号包含不可见字符，请重新输入"
        CloudSettingsManager.TopicValidation.Reason.LEADING_SLASH ->
            "房间号不能以 / 开头"
        CloudSettingsManager.TopicValidation.Reason.EMPTY_SEGMENT ->
            "房间号不能包含连续的 //"
        CloudSettingsManager.TopicValidation.Reason.TOO_LONG ->
            "房间号过长，请控制在 200 个字符以内"
    }

    /**
     * Returns a warning for the saved broker, or null when there is nothing to say.
     * Never blocks saving — a plaintext broker still works, the user just should know.
     */
    fun brokerWarning(broker: String): String? {
        val kind = CloudSettingsManager.classifyBroker(broker)
        val isPublic = CloudSettingsManager.isPublicTestBroker(broker)
        return when {
            kind == CloudSettingsManager.BrokerKind.PLAINTEXT && isPublic ->
                "当前使用公共测试服务器且未加密传输（tcp://）。消息内容仍是端到端加密的，" +
                    "但房间号和收发时间对网络中间人可见。建议改用 ssl://broker.emqx.io:8883。"
            kind == CloudSettingsManager.BrokerKind.PLAINTEXT ->
                "当前服务器未启用 TLS（tcp://）。消息内容仍是端到端加密的，" +
                    "但房间号和收发时间对网络中间人可见。建议改用 ssl:// 地址。"
            isPublic ->
                "当前使用公共测试服务器，任何人都可以连接。消息内容已端到端加密，" +
                    "请务必设置足够复杂的加密密码。"
            kind == CloudSettingsManager.BrokerKind.UNKNOWN ->
                "服务器地址格式无法识别，请确认以 ssl:// 或 tcp:// 开头。"
            else -> null
        }
    }
}
