package com.example.dsim

object SmsTagParserUtils {
    /**
    * 智能解析 MappingKey 并拼接用户卡号，生成专业的 UI 显示文本
    * @param mappingKey 底层键值 (例如 ICCID_8986... 或 DEV_xxx_SUBID_9)
    * @param remarkPhoneNumber 用户在花名册中备注的该 SIM 卡号 (例如 +86151...)
    */
    fun parseAndFormatTag(mappingKey: String?, remarkPhoneNumber: String?): String {
        if (mappingKey.isNullOrBlank()) return "来源: 未知"

        val formattedNumber = remarkPhoneNumber ?: "未备注号码"

        return when {
            mappingKey.startsWith("ICCID_") -> {
                val iccidValue = mappingKey.substringAfter("ICCID_")
                val displayIccid = "...${iccidValue.takeLast(8)}"
                "✅ CCID: $displayIccid | 本机 $formattedNumber"
            }

            mappingKey.startsWith("DEV_") && mappingKey.contains("_SUBID_") -> {
                val devIdPart = mappingKey.substringAfter("DEV_").substringBefore("_SUBID_")
                val subIdPart = mappingKey.substringAfter("_SUBID_")
                val displayDevId = "${devIdPart.take(4)}...${devIdPart.takeLast(4)}"
                "⚠️ DEV: $displayDevId (SubId: $subIdPart) | 本机 $formattedNumber"
            }

            else -> "来源: $mappingKey | 本机 $formattedNumber"
        }
    }
}
