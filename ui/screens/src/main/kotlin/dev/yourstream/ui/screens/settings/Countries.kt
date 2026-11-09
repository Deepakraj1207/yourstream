package dev.yourstream.ui.screens.settings

import java.util.Locale

/** A country for the phone number field: ISO code, calling code (digits, no "+"), localised name. */
data class Country(val iso: String, val dialCode: String, val name: String) {
    /** The flag emoji, from the two regional indicator letters of [iso]. */
    val flag: String get() = iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** Calling codes by country (pure; tested). Names come from the device locale. */
object Countries {
    // ISO 3166-1 alpha-2 → ITU calling code. Shared codes (+1, +7, +44, …) list the main country first.
    private const val TABLE =
        "US:1,CA:1,AG:1268,AI:1264,AS:1684,BB:1246,BM:1441,BS:1242,DM:1767,DO:1809,GD:1473,GU:1671,JM:1876,KN:1869,KY:1345,LC:1758," +
            "MP:1670,MS:1664,PR:1787,SX:1721,TC:1649,TT:1868,VC:1784,VG:1284,VI:1340," +
            "RU:7,KZ:7,EG:20,ZA:27,GR:30,NL:31,BE:32,FR:33,ES:34,HU:36,IT:39,RO:40,CH:41,AT:43,GB:44,DK:45,SE:46,NO:47,PL:48,DE:49," +
            "PE:51,MX:52,CU:53,AR:54,BR:55,CL:56,CO:57,VE:58,MY:60,AU:61,ID:62,PH:63,NZ:64,SG:65,TH:66,JP:81,KR:82,VN:84,CN:86,TR:90," +
            "IN:91,PK:92,AF:93,LK:94,MM:95,IR:98,SS:211,MA:212,DZ:213,TN:216,LY:218,GM:220,SN:221,MR:222,ML:223,GN:224,CI:225,BF:226," +
            "NE:227,TG:228,BJ:229,MU:230,LR:231,SL:232,GH:233,NG:234,TD:235,CF:236,CM:237,CV:238,ST:239,GQ:240,GA:241,CG:242,CD:243," +
            "AO:244,GW:245,SC:248,SD:249,RW:250,ET:251,SO:252,DJ:253,KE:254,TZ:255,UG:256,BI:257,MZ:258,ZM:260,MG:261,RE:262,ZW:263," +
            "NA:264,MW:265,LS:266,BW:267,SZ:268,KM:269,SH:290,ER:291,AW:297,FO:298,GL:299,GI:350,PT:351,LU:352,IE:353,IS:354,AL:355," +
            "MT:356,CY:357,FI:358,BG:359,LT:370,LV:371,EE:372,MD:373,AM:374,BY:375,AD:376,MC:377,SM:378,UA:380,RS:381,ME:382,XK:383," +
            "HR:385,SI:386,BA:387,MK:389,CZ:420,SK:421,LI:423,FK:500,BZ:501,GT:502,SV:503,HN:504,NI:505,CR:506,PA:507,PM:508,HT:509," +
            "GP:590,BO:591,GY:592,EC:593,GF:594,PY:595,MQ:596,SR:597,UY:598,CW:599,TL:670,NF:672,BN:673,NR:674,PG:675,TO:676,SB:677," +
            "VU:678,FJ:679,PW:680,WF:681,CK:682,NU:683,WS:685,KI:686,NC:687,TV:688,PF:689,TK:690,FM:691,MH:692,KP:850,HK:852,MO:853," +
            "KH:855,LA:856,BD:880,TW:886,MV:960,LB:961,JO:962,SY:963,IQ:964,KW:965,SA:966,YE:967,OM:968,PS:970,AE:971,IL:972,BH:973," +
            "QA:974,BT:975,MN:976,NP:977,TJ:992,TM:993,AZ:994,GE:995,KG:996,UZ:998"

    private val codes: List<Pair<String, String>> = TABLE.split(',').map { it.substringBefore(':') to it.substringAfter(':') }

    /** Every country, sorted by name in [locale]. */
    fun all(locale: Locale = Locale.getDefault()): List<Country> =
        codes.map { (iso, code) -> Country(iso, code, displayName(iso, locale)) }.sortedBy { it.name.lowercase(locale) }

    fun byIso(iso: String?, locale: Locale = Locale.getDefault()): Country? =
        iso?.uppercase()?.let { i -> codes.firstOrNull { it.first == i } }?.let { (iso2, code) -> Country(iso2, code, displayName(iso2, locale)) }

    /**
     * The country a typed calling code belongs to (exact match). A shared code keeps [current]
     * when it still matches (+1 stays Canada), else the main country for that code.
     */
    fun forDialCode(digits: String, current: Country? = null, locale: Locale = Locale.getDefault()): Country? {
        if (digits.isEmpty()) return null
        if (current != null && current.dialCode == digits) return current
        val exact = codes.firstOrNull { it.second == digits } ?: return null
        return Country(exact.first, exact.second, displayName(exact.first, locale))
    }

    /** "+91" + "98765 43210" → "+919876543210" (what Telegram receives). */
    fun international(dialCode: String, number: String): String = "+" + dialCode.filter(Char::isDigit) + number.filter(Char::isDigit)

    private fun displayName(iso: String, locale: Locale): String =
        Locale("", iso).getDisplayCountry(locale).takeIf { it.isNotBlank() && it != iso } ?: iso
}
