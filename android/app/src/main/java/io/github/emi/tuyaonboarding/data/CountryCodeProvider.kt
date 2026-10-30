package io.github.emi.tuyaonboarding.data

import android.content.Context
import com.google.i18n.phonenumbers.PhoneNumberUtil

object CountryCodeProvider {
    fun current(context: Context): String {
        val region = context.resources.configuration.locales[0].country
        check(region.isNotEmpty()) { "Phone locale has no region; cannot derive the Tuya country code" }
        val code = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)
        check(code > 0) { "Unsupported region '$region': no dialing country code" }
        return code.toString()
    }
}
