/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Best-effort mapping from the device's SIM/network country to its E.164 calling code, so the
 * Telegram sign-in screen can pre-fill the "+NN" prefix like the official apps do. No permission is
 * required to read the SIM/network country ISO. Falls back to an empty prefix when unknown.
 */



package moe.rukamori.archivetune.telegram

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

private val CALLING_CODES: Map<String, String> =
    mapOf(
        "us" to "1", "ca" to "1", "ru" to "7", "kz" to "7", "eg" to "20", "za" to "27",
        "gr" to "30", "nl" to "31", "be" to "32", "fr" to "33", "es" to "34", "hu" to "36",
        "it" to "39", "ro" to "40", "ch" to "41", "at" to "43", "gb" to "44", "dk" to "45",
        "se" to "46", "no" to "47", "pl" to "48", "de" to "49", "pe" to "51", "mx" to "52",
        "cu" to "53", "ar" to "54", "br" to "55", "cl" to "56", "co" to "57", "ve" to "58",
        "my" to "60", "au" to "61", "id" to "62", "ph" to "63", "nz" to "64", "sg" to "65",
        "th" to "66", "jp" to "81", "kr" to "82", "vn" to "84", "cn" to "86", "tr" to "90",
        "in" to "91", "pk" to "92", "af" to "93", "lk" to "94", "mm" to "95", "ir" to "98",
        "ma" to "212", "dz" to "213", "tn" to "216", "ly" to "218", "gm" to "220", "sn" to "221",
        "ng" to "234", "gh" to "233", "ke" to "254", "tz" to "255", "ug" to "256", "pt" to "351",
        "ie" to "353", "fi" to "358", "bg" to "359", "lt" to "370", "lv" to "371", "ee" to "372",
        "ua" to "380", "rs" to "381", "hr" to "385", "si" to "386", "cz" to "420", "sk" to "421",
        "il" to "972", "ae" to "971", "sa" to "966", "qa" to "974", "kw" to "965", "bh" to "973",
        "om" to "968", "jo" to "962", "lb" to "961", "iq" to "964", "hk" to "852", "tw" to "886",
        "bd" to "880", "np" to "977",
    )

/** Returns the E.164 calling code (digits only, no "+") for the device's country, or "". */
fun defaultCallingCode(context: Context): String {
    val iso =
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simCountryIso?.takeIf { it.isNotBlank() }
                ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: Locale.getDefault().country
        }.getOrNull()
            ?.lowercase(Locale.US)
            ?: return ""
    return CALLING_CODES[iso].orEmpty()
}

/** Combines a country calling code and a national number into an E.164 "+<cc><number>" string. */
fun composeE164(
    callingCode: String,
    nationalNumber: String,
): String {
    val cc = callingCode.filter(Char::isDigit)
    val number = nationalNumber.filter(Char::isDigit)
    return "+$cc$number"
}
