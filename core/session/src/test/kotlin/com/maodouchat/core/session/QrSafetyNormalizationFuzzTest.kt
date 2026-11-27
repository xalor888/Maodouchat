package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Generated from probe truth (Calib189 + RangeProbe189 + NormProbe189)
// by gen_norm_tables189.py -- do not hand-edit: Greek/fullwidth literals
// typed by sight were wrong before (U+1F9B mapped as U+1FBC, U+1FC8 as
// U+1F74, U+0391/U+039F/fullwidth B-Z/0-9 missing). Letter/digit is
// Kotlin Char.isLetterOrDigit per UTF-16 unit; astral chars arrive as
// two surrogates and are dropped. U+2C2F is NOT a letter in this
// Kotlin/JDK (Python disagrees); it is excluded. Final sigma follows
// Java/Kotlin lowercase(): scan past case-ignorable chars (all digits
// plus U+00AA/U+00BA/U+01BB/U+01C0-U+01C3, SigmaProbe189) -- the old
// immediate-neighbour model was wrong (drive run1: 42 mismatches).
private fun isLetterOrDigit189(c: Char): Boolean =
    c in '\u0030'..'\u0039' ||
    c in '\u0041'..'\u005a' ||
    c in '\u0061'..'\u007a' ||
    c == '\u00aa' ||
    c == '\u00b5' ||
    c == '\u00ba' ||
    c in '\u00c0'..'\u00d6' ||
    c in '\u00d8'..'\u00f6' ||
    c in '\u00f8'..'\u024f' ||
    c in '\u0390'..'\u03a1' ||
    c in '\u03a3'..'\u03cf' ||
    c in '\u0580'..'\u0588' ||
    c in '\u0660'..'\u0669' ||
    c in '\u06f0'..'\u06f9' ||
    c in '\u0966'..'\u096f' ||
    c in '\u10d0'..'\u10d1' ||
    c in '\u1e3c'..'\u1e3d' ||
    c == '\u1e9e' ||
    c in '\u1f80'..'\u1fb4' ||
    c in '\u1fb6'..'\u1fbc' ||
    c == '\u1fbe' ||
    c in '\u1fc2'..'\u1fc4' ||
    c in '\u1fc6'..'\u1fcc' ||
    c in '\u1fd0'..'\u1fd3' ||
    c in '\u1fd6'..'\u1fdb' ||
    c in '\u1fe0'..'\u1fec' ||
    c in '\u1ff2'..'\u1ff4' ||
    c in '\u1ff6'..'\u1ffc' ||
    c in '\u212a'..'\u212b' ||
    c in '\ua64a'..'\ua64b' ||
    c in '\ufb00'..'\ufb06' ||
    c in '\uff10'..'\uff19' ||
    c in '\uff21'..'\uff3a' ||
    c in '\uff41'..'\uff5a'
private fun isDigit189(c: Char): Boolean =
    c in '\u0030'..'\u0039' ||
    c in '\u0660'..'\u0669' ||
    c in '\u06f0'..'\u06f9' ||
    c in '\u0966'..'\u096f' ||
    c in '\uff10'..'\uff19'
private fun isCased189(c: Char): Boolean =
    c in '\u0041'..'\u005a' ||
    c in '\u0061'..'\u007a' ||
    c == '\u00b5' ||
    c in '\u00c0'..'\u00d6' ||
    c in '\u00d8'..'\u00f6' ||
    c in '\u00f8'..'\u01ba' ||
    c in '\u01bc'..'\u01bf' ||
    c in '\u01c4'..'\u024f' ||
    c in '\u0390'..'\u03a1' ||
    c in '\u03a3'..'\u03cf' ||
    c in '\u0580'..'\u0588' ||
    c in '\u10d0'..'\u10d1' ||
    c in '\u1e3c'..'\u1e3d' ||
    c == '\u1e9e' ||
    c in '\u1f80'..'\u1fb4' ||
    c in '\u1fb6'..'\u1fbc' ||
    c == '\u1fbe' ||
    c in '\u1fc2'..'\u1fc4' ||
    c in '\u1fc6'..'\u1fcc' ||
    c in '\u1fd0'..'\u1fd3' ||
    c in '\u1fd6'..'\u1fdb' ||
    c in '\u1fe0'..'\u1fec' ||
    c in '\u1ff2'..'\u1ff4' ||
    c in '\u1ff6'..'\u1ffc' ||
    c in '\u212a'..'\u212b' ||
    c in '\ua64a'..'\ua64b' ||
    c in '\ufb00'..'\ufb06' ||
    c in '\uff21'..'\uff3a' ||
    c in '\uff41'..'\uff5a'
private fun isIgnorable189(c: Char): Boolean =
    c in '\u0030'..'\u0039' ||
    c == '\u00aa' ||
    c == '\u00ba' ||
    c == '\u01bb' ||
    c in '\u01c0'..'\u01c3' ||
    c in '\u0660'..'\u0669' ||
    c in '\u06f0'..'\u06f9' ||
    c in '\u0966'..'\u096f' ||
    c in '\uff10'..'\uff19'

private fun lowerUnit189(c: Char): String = when (c) {
    in '\u0041'..'\u005a' -> (c + 32).toString()
    in '\u00c0'..'\u00de' -> (c + 32).toString()
    in '\uff21'..'\uff3a' -> (c + 32).toString()
    '\u0100' -> "\u0101"
    '\u0102' -> "\u0103"
    '\u0104' -> "\u0105"
    '\u0106' -> "\u0107"
    '\u0108' -> "\u0109"
    '\u010a' -> "\u010b"
    '\u010c' -> "\u010d"
    '\u010e' -> "\u010f"
    '\u0110' -> "\u0111"
    '\u0112' -> "\u0113"
    '\u0114' -> "\u0115"
    '\u0116' -> "\u0117"
    '\u0118' -> "\u0119"
    '\u011a' -> "\u011b"
    '\u011c' -> "\u011d"
    '\u011e' -> "\u011f"
    '\u0120' -> "\u0121"
    '\u0122' -> "\u0123"
    '\u0124' -> "\u0125"
    '\u0126' -> "\u0127"
    '\u0128' -> "\u0129"
    '\u012a' -> "\u012b"
    '\u012c' -> "\u012d"
    '\u012e' -> "\u012f"
    '\u0130' -> "\u0069\u0307"
    '\u0132' -> "\u0133"
    '\u0134' -> "\u0135"
    '\u0136' -> "\u0137"
    '\u0139' -> "\u013a"
    '\u013b' -> "\u013c"
    '\u013d' -> "\u013e"
    '\u013f' -> "\u0140"
    '\u0141' -> "\u0142"
    '\u0143' -> "\u0144"
    '\u0145' -> "\u0146"
    '\u0147' -> "\u0148"
    '\u014a' -> "\u014b"
    '\u014c' -> "\u014d"
    '\u014e' -> "\u014f"
    '\u0150' -> "\u0151"
    '\u0152' -> "\u0153"
    '\u0154' -> "\u0155"
    '\u0156' -> "\u0157"
    '\u0158' -> "\u0159"
    '\u015a' -> "\u015b"
    '\u015c' -> "\u015d"
    '\u015e' -> "\u015f"
    '\u0160' -> "\u0161"
    '\u0162' -> "\u0163"
    '\u0164' -> "\u0165"
    '\u0166' -> "\u0167"
    '\u0168' -> "\u0169"
    '\u016a' -> "\u016b"
    '\u016c' -> "\u016d"
    '\u016e' -> "\u016f"
    '\u0170' -> "\u0171"
    '\u0172' -> "\u0173"
    '\u0174' -> "\u0175"
    '\u0176' -> "\u0177"
    '\u0178' -> "\u00ff"
    '\u0179' -> "\u017a"
    '\u017b' -> "\u017c"
    '\u017d' -> "\u017e"
    '\u0181' -> "\u0253"
    '\u0182' -> "\u0183"
    '\u0184' -> "\u0185"
    '\u0186' -> "\u0254"
    '\u0187' -> "\u0188"
    '\u0189' -> "\u0256"
    '\u018a' -> "\u0257"
    '\u018b' -> "\u018c"
    '\u018e' -> "\u01dd"
    '\u018f' -> "\u0259"
    '\u0190' -> "\u025b"
    '\u0191' -> "\u0192"
    '\u0193' -> "\u0260"
    '\u0194' -> "\u0263"
    '\u0196' -> "\u0269"
    '\u0197' -> "\u0268"
    '\u0198' -> "\u0199"
    '\u019c' -> "\u026f"
    '\u019d' -> "\u0272"
    '\u019f' -> "\u0275"
    '\u01a0' -> "\u01a1"
    '\u01a2' -> "\u01a3"
    '\u01a4' -> "\u01a5"
    '\u01a6' -> "\u0280"
    '\u01a7' -> "\u01a8"
    '\u01a9' -> "\u0283"
    '\u01ac' -> "\u01ad"
    '\u01ae' -> "\u0288"
    '\u01af' -> "\u01b0"
    '\u01b1' -> "\u028a"
    '\u01b2' -> "\u028b"
    '\u01b3' -> "\u01b4"
    '\u01b5' -> "\u01b6"
    '\u01b7' -> "\u0292"
    '\u01b8' -> "\u01b9"
    '\u01bc' -> "\u01bd"
    '\u01c4' -> "\u01c6"
    '\u01c5' -> "\u01c6"
    '\u01c7' -> "\u01c9"
    '\u01c8' -> "\u01c9"
    '\u01ca' -> "\u01cc"
    '\u01cb' -> "\u01cc"
    '\u01cd' -> "\u01ce"
    '\u01cf' -> "\u01d0"
    '\u01d1' -> "\u01d2"
    '\u01d3' -> "\u01d4"
    '\u01d5' -> "\u01d6"
    '\u01d7' -> "\u01d8"
    '\u01d9' -> "\u01da"
    '\u01db' -> "\u01dc"
    '\u01de' -> "\u01df"
    '\u01e0' -> "\u01e1"
    '\u01e2' -> "\u01e3"
    '\u01e4' -> "\u01e5"
    '\u01e6' -> "\u01e7"
    '\u01e8' -> "\u01e9"
    '\u01ea' -> "\u01eb"
    '\u01ec' -> "\u01ed"
    '\u01ee' -> "\u01ef"
    '\u01f1' -> "\u01f3"
    '\u01f2' -> "\u01f3"
    '\u01f4' -> "\u01f5"
    '\u01f6' -> "\u0195"
    '\u01f7' -> "\u01bf"
    '\u01f8' -> "\u01f9"
    '\u01fa' -> "\u01fb"
    '\u01fc' -> "\u01fd"
    '\u01fe' -> "\u01ff"
    '\u0200' -> "\u0201"
    '\u0202' -> "\u0203"
    '\u0204' -> "\u0205"
    '\u0206' -> "\u0207"
    '\u0208' -> "\u0209"
    '\u020a' -> "\u020b"
    '\u020c' -> "\u020d"
    '\u020e' -> "\u020f"
    '\u0210' -> "\u0211"
    '\u0212' -> "\u0213"
    '\u0214' -> "\u0215"
    '\u0216' -> "\u0217"
    '\u0218' -> "\u0219"
    '\u021a' -> "\u021b"
    '\u021c' -> "\u021d"
    '\u021e' -> "\u021f"
    '\u0220' -> "\u019e"
    '\u0222' -> "\u0223"
    '\u0224' -> "\u0225"
    '\u0226' -> "\u0227"
    '\u0228' -> "\u0229"
    '\u022a' -> "\u022b"
    '\u022c' -> "\u022d"
    '\u022e' -> "\u022f"
    '\u0230' -> "\u0231"
    '\u0232' -> "\u0233"
    '\u023a' -> "\u2c65"
    '\u023b' -> "\u023c"
    '\u023d' -> "\u019a"
    '\u023e' -> "\u2c66"
    '\u0241' -> "\u0242"
    '\u0243' -> "\u0180"
    '\u0244' -> "\u0289"
    '\u0245' -> "\u028c"
    '\u0246' -> "\u0247"
    '\u0248' -> "\u0249"
    '\u024a' -> "\u024b"
    '\u024c' -> "\u024d"
    '\u024e' -> "\u024f"
    '\u0391' -> "\u03b1"
    '\u0392' -> "\u03b2"
    '\u0393' -> "\u03b3"
    '\u0394' -> "\u03b4"
    '\u0395' -> "\u03b5"
    '\u0396' -> "\u03b6"
    '\u0397' -> "\u03b7"
    '\u0398' -> "\u03b8"
    '\u0399' -> "\u03b9"
    '\u039a' -> "\u03ba"
    '\u039b' -> "\u03bb"
    '\u039c' -> "\u03bc"
    '\u039d' -> "\u03bd"
    '\u039e' -> "\u03be"
    '\u039f' -> "\u03bf"
    '\u03a0' -> "\u03c0"
    '\u03a1' -> "\u03c1"
    '\u03a3' -> "\u03c3"
    '\u03a4' -> "\u03c4"
    '\u03a5' -> "\u03c5"
    '\u03a6' -> "\u03c6"
    '\u03a7' -> "\u03c7"
    '\u03a8' -> "\u03c8"
    '\u03a9' -> "\u03c9"
    '\u03aa' -> "\u03ca"
    '\u03ab' -> "\u03cb"
    '\u03cf' -> "\u03d7"
    '\u1e3c' -> "\u1e3d"
    '\u1e9e' -> "\u00df"
    '\u1f88' -> "\u1f80"
    '\u1f89' -> "\u1f81"
    '\u1f8a' -> "\u1f82"
    '\u1f8b' -> "\u1f83"
    '\u1f8c' -> "\u1f84"
    '\u1f8d' -> "\u1f85"
    '\u1f8e' -> "\u1f86"
    '\u1f8f' -> "\u1f87"
    '\u1f98' -> "\u1f90"
    '\u1f99' -> "\u1f91"
    '\u1f9a' -> "\u1f92"
    '\u1f9b' -> "\u1f93"
    '\u1f9c' -> "\u1f94"
    '\u1f9d' -> "\u1f95"
    '\u1f9e' -> "\u1f96"
    '\u1f9f' -> "\u1f97"
    '\u1fa8' -> "\u1fa0"
    '\u1fa9' -> "\u1fa1"
    '\u1faa' -> "\u1fa2"
    '\u1fab' -> "\u1fa3"
    '\u1fac' -> "\u1fa4"
    '\u1fad' -> "\u1fa5"
    '\u1fae' -> "\u1fa6"
    '\u1faf' -> "\u1fa7"
    '\u1fb8' -> "\u1fb0"
    '\u1fb9' -> "\u1fb1"
    '\u1fba' -> "\u1f70"
    '\u1fbb' -> "\u1f71"
    '\u1fbc' -> "\u1fb3"
    '\u1fc8' -> "\u1f72"
    '\u1fc9' -> "\u1f73"
    '\u1fca' -> "\u1f74"
    '\u1fcb' -> "\u1f75"
    '\u1fcc' -> "\u1fc3"
    '\u1fd8' -> "\u1fd0"
    '\u1fd9' -> "\u1fd1"
    '\u1fda' -> "\u1f76"
    '\u1fdb' -> "\u1f77"
    '\u1fe8' -> "\u1fe0"
    '\u1fe9' -> "\u1fe1"
    '\u1fea' -> "\u1f7a"
    '\u1feb' -> "\u1f7b"
    '\u1fec' -> "\u1fe5"
    '\u1ff8' -> "\u1f78"
    '\u1ff9' -> "\u1f79"
    '\u1ffa' -> "\u1f7c"
    '\u1ffb' -> "\u1f7d"
    '\u1ffc' -> "\u1ff3"
    '\u212a' -> "\u006b"
    '\u212b' -> "\u00e5"
    '\ua64a' -> "\ua64b"
    else -> c.toString()
}

private fun normalizedFingerprintOracle189(s: String): String {
    val kept = ArrayList<Char>()
    for (c in s) {
        if (isLetterOrDigit189(c)) kept.add(c)
    }
    val lowered = StringBuilder()
    for (c in kept) lowered.append(lowerUnit189(c))
    // Word-final sigma: a Σ lowercases to ς when preceded by a cased
    // letter and not followed by one (see applyFinalSigma189).
    return applyFinalSigma189(kept, lowered.toString())
}

private fun applyFinalSigma189(kept: List<Char>, lowered: String): String {
    val srcIndex = ArrayList<Int>()
    for ((idx, c) in kept.withIndex()) {
        repeat(lowerUnit189(c).length) { srcIndex.add(idx) }
    }
    val chars = lowered.toCharArray()
    for (pos in chars.indices) {
        if (chars[pos] != '\u03c3') continue
        val src = srcIndex[pos]
        if (kept[src] != '\u03a3') continue
        // lowercase() scans past case-ignorable chars in both directions;
        // only a cased letter stops the scan. Digits between sigma and
        // the next letter do not block it (e.g. "ΑΣ9Α" -> "ασ9α").
        var j = src - 1
        while (j >= 0 && isIgnorable189(kept[j])) j--
        val beforeCased = j >= 0 && isCased189(kept[j])
        j = src + 1
        while (j < kept.size && isIgnorable189(kept[j])) j++
        val afterCased = j < kept.size && isCased189(kept[j])
        if (beforeCased && !afterCased) chars[pos] = '\u03c2'
    }
    return String(chars)
}

private fun normalizedSafetyCodeOracle189(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        if (isDigit189(c)) sb.append(c)
    }
    return sb.toString()
}

// Generator alphabet: probe-verified units plus astral pairs and lone
// surrogates (both dropped by the filter, kept to exercise surrogate paths).
private val NORM_ALPHABET189 = listOf(
"a",
    "B",
    "z",
    "0",
    "9",
    " ",
    "-",
    "_",
    ".",
    ":",
    "/",
    "+",
    "@",
    "#",
    "\u0009",
    "\u000a",
    "\u00a0",
    "\u00b2",
    "\u00bd",
    "\u2160",
    "\u200b",
    "\ufeff",
    "\u0085",
    "\u00c0",
    "\u00e0",
    "\u00df",
    "\u0130",
    "\u0131",
    "\u017f",
    "\u1e9e",
    "\u03a3",
    "\u03c3",
    "\u03c2",
    "\u1f88",
    "\u1f9b",
    "\u1fbc",
    "\u1fc8",
    "\u0587",
    "\ufb00",
    "\ufb06",
    "\u212a",
    "\u212b",
    "\uff21",
    "\uff22",
    "\uff23",
    "\uff41",
    "\uff42",
    "\uff43",
    "\u0100",
    "\u0101",
    "\u012e",
    "\u012f",
    "\u01c5",
    "\u01c6",
    "\u01f1",
    "\u01f2",
    "\u01f3",
    "\u00e9",
    "\u00e8",
    "\u00ea",
    "\u00eb",
    "\u00fc",
    "\u00f6",
    "\u00f1",
    "\u00e7",
    "\u00ff",
    "\u00fe",
    "\u00f0",
    "\u00f8",
    "\u00e5",
    "\u00c9",
    "\u00dc",
    "\u00d6",
    "\u00d1",
    "\u00c7",
    "\u00de",
    "\u00d0",
    "\u00d8",
    "\u00c5",
    "\u1e3c",
    "\u1e3d",
    "\ua64a",
    "\ua64b",
    "\u10d0",
    "\u10d1",
    "\u0660",
    "\u0665",
    "\u06f9",
    "\u0966",
    "\u096f",
    "\uff10",
    "\uff19",
    "\ud835\udc00",
    "\ud835\udfce",
    "\ud83d\ude00",
    "\ud800",
    "\udc00"
)

private fun randomNormString189(r: Random): String {
    val n = r.nextInt(0, 25)
    val sb = StringBuilder()
    repeat(n) { sb.append(NORM_ALPHABET189.random(r)) }
    return sb.toString()
}

// ---------- tests ----------

class QrSafetyNormalizationFuzzTest {

    @Test
    fun normalizedFingerprintDifferential189() {
        val r = Random(189002)
        repeat(300) {
            val s = randomNormString189(r)
            assertEquals(normalizedFingerprintOracle189(s), s.normalizedFingerprint())
        }
    }

    @Test
    fun normalizedSafetyCodeDifferential189() {
        val r = Random(189003)
        repeat(300) {
            val s = randomNormString189(r)
            assertEquals(normalizedSafetyCodeOracle189(s), s.normalizedSafetyCode())
        }
    }

    @Test
    fun qrSafetyNormalizationPinned189() {
        val cases = listOf(
            Triple("", "", ""),
            Triple("ABC-123", "abc123", "123"),
            Triple("\u0130", "i\u0307", ""),
            Triple("\u03a3\u03a3", "\u03c3\u03c2", ""),
            Triple("\u00df\u1e9e", "\u00df\u00df", ""),
            Triple("\ufb00", "\ufb00", ""),
            Triple("K\u212a", "kk", ""),
            Triple("\uff21\uff22\uff23", "\uff41\uff42\uff43", ""),
            Triple("\uff10\uff11\uff12\uff13", "\uff10\uff11\uff12\uff13", "\uff10\uff11\uff12\uff13"),
            Triple("\u0660\u0661\u0662\u0663", "\u0660\u0661\u0662\u0663", "\u0660\u0661\u0662\u0663"),
            Triple("\u00b2\u00bd", "", ""),
            Triple("\u2160", "", ""),
            Triple("\ud835\udc00", "", ""),
            Triple("\ud835\udfce", "", ""),
            Triple("\ud800", "", ""),
            Triple("a\ud800b", "ab", ""),
            Triple("  A B  ", "ab", ""),
            Triple("\u00a0\u200b\ufeff", "", ""),
            Triple("\u01c5", "\u01c6", ""),
            Triple("\u1f88", "\u1f80", ""),
            Triple("\ua64a", "\ua64b", ""),
            Triple("A\u0307", "a", ""),
            Triple("12 34", "1234", "1234"),
            Triple("AB-12-CD", "ab12cd", "12"),
            Triple("\u2c2f", "", ""),
            Triple("\u1f9b", "\u1f93", ""),
            Triple("\u1fc8", "\u1f72", ""),
            Triple("\u212b", "\u00e5", ""),
            Triple("\u0100\u0101\u012a\u012b", "\u0101\u0101\u012b\u012b", ""),
            Triple("\u0391\u03a3", "\u03b1\u03c2", ""),
            Triple("\u039f\u03a3 \u0391\u03a3", "\u03bf\u03c3\u03b1\u03c2", ""),
            Triple("\u03a3A", "\u03c3a", ""),
            Triple("A\u03a3", "a\u03c2", ""),
            Triple("\u1fbc", "\u1fb3", ""),
            Triple("\uff26\uff26", "\uff46\uff46", ""),
            Triple("\uff19", "\uff19", "\uff19"),
            Triple("\u00c5\u212b", "\u00e5\u00e5", ""),
            Triple("\ufb00\ufb06", "\ufb00\ufb06", ""),
            Triple("\u0587", "\u0587", ""),
            Triple("\ua64a\ua64b", "\ua64b\ua64b", ""),
            Triple("\u10d0", "\u10d0", ""),
            Triple("\u00c9", "\u00e9", ""),
            Triple("\u00ff", "\u00ff", ""),
            Triple("\u00de", "\u00fe", ""),
        )
        for ((input, fp, code) in cases) {
            assertEquals(fp, input.normalizedFingerprint())
            assertEquals(code, input.normalizedSafetyCode())
            assertEquals(fp, normalizedFingerprintOracle189(input))
            assertEquals(code, normalizedSafetyCodeOracle189(input))
        }
    }
}
