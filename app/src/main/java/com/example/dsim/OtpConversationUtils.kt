package com.example.dsim

import android.content.Context
import com.example.dsim.database.SmsMessage
import java.util.Locale
import kotlin.math.abs

data class OtpMessageItem(
    val sms: SmsMessage,
    val senderLabel: String,
    val code: String,
    val sourceLabel: String,
    val previewBody: String
)

object OtpConversationUtils {
    private val defaultKeywords = setOf(
        "验证码",
        "校验码",
        "动态码",
        "驗證碼",
        "認證碼",
        "otp",
        "one-time password",
        "verification code",
        "verify code",
        "security code",
        "login code"
    )

    private val genericBracketLabels = setOf(
        "验证码",
        "校验码",
        "动态码",
        "通知",
        "消息",
        "提醒",
        "短信"
    )

    private val bracketSenderRegex = Regex("[【\\[]([^】\\]]{1,24})[】\\]]")
    private val digitCodeRegex = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val mixedCodeRegex = Regex("(?i)\\b([A-Z0-9]{4,10})\\b")

    fun buildOtpItems(
        context: Context,
        messages: List<SmsMessage>
    ): List<OtpMessageItem> {
        val settings = OtpRulesStore.loadSettings(context)
        val overrides = OtpRulesStore.getAllOverrides(context)
        return messages
            .asSequence()
            .filter { it.type == 1 }
            .mapNotNull { sms ->
                toOtpItem(context, sms, settings, overrides[sms.uuid])
            }
            .sortedBy { it.sms.timestamp }
            .toList()
    }

    fun toOtpItem(
        context: Context,
        sms: SmsMessage,
        settings: OtpRuleSettings,
        override: OtpMessageOverride?
    ): OtpMessageItem? {
        val body = sms.body.trim()
        if (body.isBlank()) {
            return null
        }

        val code = extractOtpCode(body)
        if (code == null) {
            return null
        }

        val isOtp = when (override?.forceOtp) {
            true -> true
            false -> false
            null -> matchesOtpByRules(settings, body)
        }
        if (!isOtp) {
            return null
        }

        val senderLabel = override?.senderLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: extractBracketSender(body)
            ?: sms.address.ifBlank { "未知来源" }

        val sourceLabel = sms.address.ifBlank { "未知通道" }
        val previewBody = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

        return OtpMessageItem(
            sms = sms,
            senderLabel = senderLabel,
            code = code,
            sourceLabel = sourceLabel,
            previewBody = previewBody,
        )
    }

    fun matchesOtpByRules(settings: OtpRuleSettings, body: String): Boolean {
        val normalized = body.lowercase(Locale.ROOT)
        if (settings.excludeKeywords.any { keyword -> normalized.contains(keyword.trim().lowercase(Locale.ROOT)) }) {
            return false
        }

        val code = extractOtpCode(body) ?: return false
        val keywords = defaultKeywords + settings.includeKeywords
        return keywords.any { keyword -> normalized.contains(keyword.trim().lowercase(Locale.ROOT)) } &&
            body.contains(code)
    }

    fun extractBracketSender(body: String): String? {
        return bracketSenderRegex.findAll(body)
            .map { it.groupValues[1].trim() }
            .map { it.replace(Regex("\\s+"), "") }
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    candidate !in genericBracketLabels &&
                    !candidate.all { it.isDigit() }
            }
    }

    fun extractOtpCode(body: String): String? {
        val keywordAnchor = findKeywordAnchor(body.lowercase(Locale.ROOT))
        val digitMatches = digitCodeRegex.findAll(body).toList()
        if (digitMatches.isNotEmpty()) {
            return selectClosestMatch(digitMatches, keywordAnchor)?.groupValues?.get(1)
        }

        val mixedMatches = mixedCodeRegex.findAll(body)
            .filter { match ->
                val value = match.groupValues[1]
                value.any { it.isDigit() } && value.any { it.isLetter() }
            }
            .toList()
        return selectClosestMatch(mixedMatches, keywordAnchor)?.groupValues?.get(1)
    }

    private fun findKeywordAnchor(normalizedBody: String): Int {
        val keywordPositions = defaultKeywords
            .map { normalizedBody.indexOf(it.lowercase(Locale.ROOT)) }
            .filter { it >= 0 }
        return keywordPositions.minOrNull() ?: Int.MAX_VALUE
    }

    private fun selectClosestMatch(
        matches: List<MatchResult>,
        keywordAnchor: Int
    ): MatchResult? {
        if (matches.isEmpty()) {
            return null
        }
        if (keywordAnchor == Int.MAX_VALUE) {
            return matches.first()
        }
        return matches.minByOrNull { match -> abs(match.range.first - keywordAnchor) }
    }
}
