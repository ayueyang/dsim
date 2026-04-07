package com.example.dsim

import android.content.Context
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

object GlobalNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun formatToE164(context: Context, number: String, defaultRegion: String = Locale.getDefault().country): String {
        if (number.isBlank()) return ""
        try {
            val parsedNumber = phoneUtil.parse(number, defaultRegion)
            
            if (!phoneUtil.isValidNumber(parsedNumber)) {
                return number.replace(" ", "").replace("-", "")
            }

            return phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: NumberParseException) {
            return number.replace(" ", "").replace("-", "")
        }
    }
}
