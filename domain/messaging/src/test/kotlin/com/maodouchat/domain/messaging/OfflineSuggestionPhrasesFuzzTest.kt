// Fuzz / random-oracle tests for OfflineSuggestionPhrases (checklist line 931,
// 79th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. match order is greeting -> life -> play -> 3-line fallback; greeting starts with a question-first branch; offlineHas is key.lowercase() in lower OR key in seed; tones are formal (～ -> 。, 嗯嗯， -> 好的，), concise (take 14), humorous (append " :)"), warm (append ～ unless already 。/～); result is distinct() then take(4). The 106-branch reply tables are rendered mechanically from the production files (see extract_phrases.py).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineSuggestionPhrasesFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- extra helpers, block 79 ----------

private fun endsWithOracle(s: String, suffix: String): Boolean {
    if (suffix.length > s.length) return false
    for (i in suffix.indices) {
        if (s[s.length - suffix.length + i] != suffix[i]) return false
    }
    return true
}

// Literal (non-regex) replace-all, as String.replace(old, new).
private fun replaceLiteralOracle(s: String, old: String, new: String): String {
    if (old.isEmpty()) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i <= s.length - old.length) {
        var ok = true
        for (j in old.indices) {
            if (s[i + j] != old[j]) { ok = false; break }
        }
        if (ok) {
            sb.append(new)
            i += old.length
        } else {
            sb.append(s[i])
            i++
        }
    }
    while (i < s.length) {
        sb.append(s[i])
        i++
    }
    return sb.toString()
}

// Order-preserving dedupe, as List.distinct().
private fun distinctOracle(xs: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (x in xs) {
        var seen = false
        for (y in out) {
            if (equalsOracle(x, y)) { seen = true; break }
        }
        if (!seen) out.add(x)
    }
    return out
}

// Float clamp with the exact coerceIn semantics: NaN passes through
// (both comparisons false), -0.0f stays -0.0f.
private fun clamp01Oracle(v: Float): Float =
    if (v < 0f) 0f else if (v > 1f) 1f else v

// SHA-256 hex, lowercase, two digits per byte (hand-rolled nibbles,
// never String.format).
private fun sha256HexOracle(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    val hash = digest.digest()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(hash.size * 2)
    for (b in hash) {
        sb.append(hex[(b.toInt() shr 4) and 0xF])
        sb.append(hex[b.toInt() and 0xF])
    }
    return sb.toString()
}


// ---------- OfflineSuggestionPhrases oracle ----------

private data class PhraseBranch79(val keys: List<String>?, val replies: List<String>)

private val GREETING_BRANCHES79 = listOf(
    PhraseBranch79(null, listOf("好问题，我的想法是……", "可以，稍等我整理一下再回你。", "我更倾向这个方向，你觉得呢？")),
    PhraseBranch79(listOf("谢谢", "thanks", "thank"), listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。")),
    PhraseBranch79(listOf("你好", "hello", "hi ", "在吗", "在不在"), listOf("在的，怎么啦？", "嗨，刚看到～", "在呢，说吧。")),
    PhraseBranch79(listOf("约", "见面", "吃饭", "电影"), listOf("时间地点你定，我配合～", "可以啊，周末怎么样？", "我想去，细节再敲定。")),
    PhraseBranch79(listOf("难过", "伤心", "累", "压力"), listOf("我在这儿听你说。", "辛苦了，先歇一会儿吧。", "需要的话我陪你聊聊。")),
    PhraseBranch79(listOf("ok", "okay", "good night", "gn", "晚安", "好的", "行"), listOf("好的，收到。", "嗯嗯，晚点聊。", "Alright, talk soon.")),
    PhraseBranch79(listOf("sorry", "抱歉", "不好意思", "对不起"), listOf("没关系。", "理解你，没事的。", "It's okay, no worries.")),
    PhraseBranch79(listOf("love", "喜欢", "爱你", "么么"), listOf("我也是～", "收到满满的喜欢。", "同样的感觉。")),
)

private val LIFE_BRANCHES79 = listOf(
    PhraseBranch79(listOf("meeting", "会议", "开会", "sync"), listOf("好的，我改一下时间。", "议程我稍后发你。", "Can we do a short call?")),
    PhraseBranch79(listOf("price", "多少钱", "费用", "报价"), listOf("我整理一版报价给你。", "方便说下预算范围吗？", "Let me send options.")),
    PhraseBranch79(listOf("photo", "图片", "照片", "看看"), listOf("发我看看～", "收到，我仔细看下。", "Looks good!")),
    PhraseBranch79(listOf("code", "bug", "报错", "崩溃", "error"), listOf("日志发我一段。", "我这边复现一下。", "Might be a race; checking.")),
    PhraseBranch79(listOf("weather", "天气", "下雨", "温度"), listOf("记得看下天气预报再出门～", "要不要改成室内活动？", "我这边帮你记着关注天气变化。")),
    PhraseBranch79(listOf("deadline", "截止", "ddl", "明天交"), listOf("截止日期我记下了，要不要拆成待办？", "先列三点关键路径，我陪你盯进度。", "需要我帮你写个简短提醒吗？")),
    PhraseBranch79(listOf("travel", "出差", "高铁", "飞机", "酒店"), listOf("行程我可以帮你整理成清单。", "要不要同步一下出发/到达时间？", "路上注意安全，到了报个平安～")),
    PhraseBranch79(listOf("health", "感冒", "生病", "医院", "吃药"), listOf("多休息喝温水，严重就去看医生。", "需要我帮你请假话术吗？", "好好照顾自己，别硬撑。")),
    PhraseBranch79(listOf("weekend", "周末", "假期", "vacation", "holiday"), listOf("周末有什么安排？", "好好休息，周一见。", "Any fun plans this weekend?")),
    PhraseBranch79(listOf("congrats", "恭喜", "祝贺", "庆祝", "offer"), listOf("太棒了，恭喜你！", "值得好好庆祝一下。", "Congrats — well earned!")),
    PhraseBranch79(listOf("traffic", "堵车", "迟到", "晚到", "delay"), listOf("注意安全，到了说一声。", "没关系，我可以等你。", "Safe travels, take your time.")),
    PhraseBranch79(listOf("food", "吃饭", "外卖", "餐厅", "lunch", "dinner"), listOf("要一起点外卖吗？", "我可以帮你列几个选项。", "I'm hungry too — any preference?")),
    PhraseBranch79(listOf("game", "游戏", "开黑", "上分", "match"), listOf("现在开一局？", "我准备好了，你定模式。", "Queue up, I'm in.")),
    PhraseBranch79(listOf("movie", "电影", "剧", "追剧", "netflix"), listOf("有推荐的片单吗？", "今晚一起看？", "Send the title, I'll check.")),
    PhraseBranch79(listOf("work", "加班", " ent", "项目", "deadline"), listOf("进度我记下了，需要帮忙拆任务吗？", "先聚焦最关键的一件事。", "Want a quick status checklist?")),
    PhraseBranch79(listOf("money", "转账", "付款", "账单", "pay"), listOf("金额确认后我再操作。", "发我账单明细～", "I'll confirm and get back.")),
    PhraseBranch79(listOf("birthday", "生日快乐", "过生"), listOf("生日快乐！今天过得开心点。", "要不要一起安排个小庆祝？", "送你一个虚拟蛋糕")),
    PhraseBranch79(listOf("study", "学习", "考试", "exam", "homework"), listOf("要不要一起复盘一下重点？", "先休息五分钟再继续。", "I can quiz you on the hard parts.")),
    PhraseBranch79(listOf("sport", "跑步", "健身", "gym", "workout"), listOf("今天练哪一块？", "加油，注意拉伸。", "Send me your PR, I want to cheer.")),
    PhraseBranch79(listOf("music", "歌", "playlist", "演唱会"), listOf("发我歌单听听。", "这首循环了好几遍。", "Any new recommendations?")),
    PhraseBranch79(listOf("pet", "猫", "狗", "铲屎", "puppy", "kitty"), listOf("毛孩子今天乖不乖？", "求吸猫/吸狗现场。", "Pet tax please ")),
    PhraseBranch79(listOf("secret", "密聊", "加密", "e2ee"), listOf("敏感内容我们用密聊说。", "记得开阅后即焚。", "I'll keep this private.")),
    PhraseBranch79(listOf("sleep", "失眠", "困", "熬夜", "insomnia"), listOf("早点休息，明天再说。", "我先不打扰你了。", "Sleep well — talk tomorrow.")),
    PhraseBranch79(listOf("coffee", "咖啡", "奶茶", "tea"), listOf("来一杯提神？", "我请你喝。", "Coffee or tea?")),
    PhraseBranch79(listOf("rain", "下雪", "台风", "storm"), listOf("出门记得带伞。", "注意安全。", "Stay dry out there.")),
    PhraseBranch79(listOf("meeting cancel", "取消", "改期", "reschedule"), listOf("那我们另约时间。", "收到，我改日历了。", "No problem — propose a new slot.")),
    PhraseBranch79(listOf("flight", "航班", "高铁", "train", "airport"), listOf("一路顺风，落地报平安。", "需要我帮你看时刻表吗？", "Safe travels — ping me when you land.")),
    PhraseBranch79(listOf("wifi", "网络", "断网", "lag", "卡顿"), listOf("可能是网络波动，稍后再试。", "我这边也有点卡。", "Try switching networks?")),
    PhraseBranch79(listOf("gift", "礼物", "惊喜", "present"), listOf("要不要一起挑个礼物？", "保密，别剧透～", "I have an idea — call me.")),
    PhraseBranch79(listOf("interview", "面试", "offer", "hr"), listOf("祝你顺利，稳住发挥。", "需要我帮你过一遍常见问题吗？", "You've got this — knock them out.")),
    PhraseBranch79(listOf("battery", "没电", "充电", "low battery"), listOf("快没电了，我先去充电。", "回头再聊～", "Powering up — brb.")),
    PhraseBranch79(listOf("map", "迷路", "导航", "lost", "directions"), listOf("发我定位，我帮你看。", "别急，先找个地标。", "Share your pin, I'll guide you.")),
    PhraseBranch79(listOf("package", "快递", "外卖到了", "delivery"), listOf("收到了说一声。", "我下楼拿。", "I'll grab it.")),
    PhraseBranch79(listOf("//", "code review", "pr ", "merge"), listOf("我晚点看你的 PR。", "有冲突先 rebase 一下。", "LGTM with nits — shipping.")),
    PhraseBranch79(listOf("cook", "做饭", "菜谱", "recipe"), listOf("今晚想吃什么？", "发我菜谱链接～", "I can help plan the menu.")),
    PhraseBranch79(listOf("plant", "浇花", "绿植", "garden"), listOf("别忘了浇水。", "新芽发了吗？", "Plant tax photos please.")),
    PhraseBranch79(listOf("book", "读书", "小说", "reading"), listOf("最近在看什么？", "读完安利我。", "Drop the title — adding to my list.")),
    PhraseBranch79(listOf("gym fail", "没去练", "偷懒", "rest day"), listOf("休息也是训练的一部分。", "明天补上就好。", "Rest day accepted.")),
    PhraseBranch79(listOf("password", "密码", "2fa", "验证码", "totp"), listOf("别在群里发验证码。", "建议开 TOTP 两步验证。", "Reset via secure channel only.")),
    PhraseBranch79(listOf("screenshot", "截图", "录屏", "screen record"), listOf("密聊请勿截图。", "有盲水印可追溯。", "Use view-once if sensitive.")),
    PhraseBranch79(listOf("budget", "预算", "省钱", "理财"), listOf("我们列个简单预算表。", "先区分必要与可选开支。", "Want a 3-line budget?")),
    PhraseBranch79(listOf("doctor", "医院", "挂号", "clinic"), listOf("早去排队，记得带证件。", "需要我陪你吗？", "Feel better soon.")),
    PhraseBranch79(listOf("parking", "停车", "挪车", "garage"), listOf("我马上挪一下。", "发我位置。", "On my way to move it.")),
    PhraseBranch79(listOf("vpn", "代理", "翻墙", "proxy"), listOf("注意账号安全，别分享节点。", "优先用官方通道。", "Keep credentials private.")),
)

private val PLAY_BRANCHES79 = listOf(
    PhraseBranch79(listOf("backup", "备份", "导出聊天", "export chat"), listOf("密聊内容不建议明文导出。", "可用加密备份方案。", "Prefer encrypted backups only.")),
    PhraseBranch79(listOf("pin message", "unpin message", "message pin"), listOf("Pinned for the group.", "Unpinned — no longer sticky.", "Pin the important update.")),
    PhraseBranch79(listOf("revoke message", "delete message", "unsend"), listOf("Revoked — pretend you didn't see it.", "Deleted on my side.", "I'll unsend that.")),
    PhraseBranch79(listOf("pin the mood", "revoke rush", "secret signal"), listOf("Pin the mood for today.", "Revoke rush starts now.", "Secret signal received.")),
    PhraseBranch79(listOf("doc hunt", "meaning race", "insight sprint", "ai file", "semantic search", "analyze file"), listOf("Doc hunt - find the clause.", "Meaning race - semantic win.", "Insight sprint - one key takeaway.", "AI file analysis is admin-gated.", "Semantic search can be limited.")),
    PhraseBranch79(listOf("pixel quest", "assist circle", "decision dash", "ai analyze", "group assistant", "image analyze"), listOf("Pixel quest - find the clue in the photo.", "Assist circle - group AI recap.", "Decision dash - pick next steps.", "AI image analysis is admin-gated.", "Group assistant can be limited.")),
    PhraseBranch79(listOf("suggest circle", "voice race", "reply sprint", "ai suggest", "ai transcribe", "suggest replies"), listOf("Suggest circle - share a quick reply idea.", "Voice race - short clear note.", "Reply sprint - three options fast.", "AI suggest replies is admin-gated.", "AI transcribe can be limited.")),
    PhraseBranch79(listOf("photo race", "clip dash", "frame hunt", "summary circle", "rewrite relay", "prompt sprint", "image send", "video send", "ai summary", "ai rewrite"), listOf("Photo race - first clear snap.", "Clip dash - short video win.", "Frame hunt - find the detail.", "Summary circle - one-line recap.", "Rewrite relay - polish the draft.", "Prompt sprint - ask better.")),
    PhraseBranch79(listOf("pin drop", "file relay", "map dash", "vault lock", "watermark hunt", "secure sprint", "secret chat", "screen secure"), listOf("Pin drop - share a static pin.", "File relay - pass the document.", "Map dash - race the route.", "Vault lock - secret chat on.", "Watermark hunt - find the mark.", "Secure sprint - FLAG_SECURE active.")),
    PhraseBranch79(listOf("spoiler race", "blur battle", "download dash", "spoiler media", "auto download"), listOf("Spoiler race - no peeking.", "Blur battle - guess the shot.", "Download dash - save on wifi.", "Spoiler media is admin-gated.", "Auto-download can be limited.")),
    PhraseBranch79(listOf("qr quest", "contact swap", "scan sprint", "qr code", "contact card"), listOf("QR quest - frame and scan.", "Contact swap - share cards carefully.", "Scan sprint - steady hands.", "QR codes stay optional.", "Contact cards are admin-gated.")),
    PhraseBranch79(listOf("nudge dash", "code check", "trust sprint", "nudge", "safety code"), listOf("Nudge dash - double-tap race.", "Code check - compare digits offline.", "Trust sprint - verify safety code.", "Nudge is a light poke, not a call.", "Safety codes stay on-device.")),
    PhraseBranch79(listOf("invite race", "mention mayhem", "link hunt", "group invite", "mentions"), listOf("Invite race starts now.", "Mention mayhem - tag carefully.", "Link hunt - find the clue.", "Invite link is ready.", "Mentions stay private in E2EE.")),
    PhraseBranch79(listOf("idea relay", "tempo tap", "translate relay", "drafts", "ai translate"), listOf("Idea relay - pass one idea.", "Tempo tap - keep the beat.", "Translate relay - next language.", "Draft saved on this device.", "AI translate is ready.")),
    PhraseBranch79(listOf("mood meter", "focus sprint", "gratitude round", "polls", "app lock"), listOf("Rate the mood meter 1-10.", "Focus sprint - set a timer.", "Gratitude round: one win.", "Quick poll is ready.", "App lock keeps the session private.")),
    PhraseBranch79(listOf("chat lock", "lock chat", "pin lock"), listOf("Chat lock is on — enter PIN.", "Unlock when you're ready.", "Keep the lock PIN private.")),
    PhraseBranch79(listOf("edit message", "message edit", "typo fix"), listOf("Edited — fixed the typo.", "Edit window is short, act fast.", "I'll edit that message.")),
    PhraseBranch79(listOf("code breaker", "silly law", "emoji math"), listOf("Code breaker — four digits.", "New silly law for the group.", "Emoji math — solve it.")),
    PhraseBranch79(listOf("mute chat", "unmute", "notifications off"), listOf("Muted this chat for focus.", "Unmute when free.", "Silence is intentional.")),
    PhraseBranch79(listOf("disappear", "disappearing", "auto delete", "阅后即焚"), listOf("Timer is set — messages will vanish.", "Use a short timer for sensitive stuff.", "Disappearing messages keep history light.")),
    PhraseBranch79(listOf("impulse draw", "word scramble", "reaction duel"), listOf("Impulse draw — lucky you?", "Unscramble this word.", "Reaction duel — pick a side.")),
    PhraseBranch79(listOf("pin chat", "pinned", "unpin"), listOf("Pinned so I don't lose it.", "Unpin when done.", "Pin the important thread.")),
    PhraseBranch79(listOf("marked unread", "mark unread", "unread later"), listOf("Marked unread for later.", "I'll clear it when I finish.", "Unread badge is intentional.")),
    PhraseBranch79(listOf("mirror echo", "sync clap", "fact or fiction"), listOf("Mirror echo — reverse me.", "Sync clap on three.", "Fact or fiction — guess!")),
    PhraseBranch79(listOf("archive", "archived", "inbox"), listOf("Archived — ping if urgent.", "I'll unarchive later.", "Inbox zero-ish after archive.")),
    PhraseBranch79(listOf("nearby", "around me", "local people"), listOf("Nearby is optional privacy-wise.", "Turn radius down if crowded.", "Sharing location only when needed.")),
    PhraseBranch79(listOf("debate", "emoji story", "quick poll"), listOf("Debate flash — pick a side.", "Emoji story round!", "Quick poll — vote now.")),
    PhraseBranch79(listOf("moments", "posts", "timeline"), listOf("Check my latest post when free.", "Moments can wait — chatting first.", "I just shared something on Moments.")),
    PhraseBranch79(listOf("block", "report", "spam", "harass"), listOf("You can block or report if needed.", "Safety first — don't tolerate abuse.", "I can help you report this.")),
    PhraseBranch79(listOf("alphabet", "silent movie", "color word"), listOf("Alphabet race — your turn.", "Silent movie round next.", "Color-word challenge accepted.")),
    PhraseBranch79(listOf("sticker", "表情包", "emoji pack"), listOf("发一个合适的贴纸？", "这个表情包绝了。", "Sticker energy.")),
    PhraseBranch79(listOf("silent", "无声", "免打扰", "dnd"), listOf("我用无声发送，不吵你。", "先免打扰，晚点聊。", "Sending silently.")),
    PhraseBranch79(listOf("watermark", "盲水印", "取证"), listOf("截图会有盲水印痕迹。", "后台可提取水印信息。", "Forensics-ready.")),
    PhraseBranch79(listOf("打电话", "call me", "视频通话", "voice call", "facetime"), listOf("我现在方便接电话。", "改文字聊也可以。", "Want a quick call?")),
    PhraseBranch79(listOf("定时", "稍后发", "schedule", "remind me later"), listOf("我设个定时消息。", "到点我提醒你。", "I'll schedule it.")),
    PhraseBranch79(listOf("群公告", "announcement", "置顶", "pin this"), listOf("建议置顶关键信息。", "我来发一版群公告草稿。", "Pin the summary?")),
    PhraseBranch79(listOf("阅后即焚", "view once", "看完即焚", "viewonce"), listOf("敏感图用阅后即焚发。", "看完就没了，注意隐私。", "Sending as view-once.")),
    PhraseBranch79(listOf("实时位置", "live location", "共享位置", "share location"), listOf("我开了实时位置，到了关。", "只共享一会儿。", "Sharing live location briefly.")),
    PhraseBranch79(listOf("机器人", "bot api", "webhook", "开发者"), listOf("可以自助接入机器人。", "Webhook 记得验签。", "Check the bot developer docs.")),
    PhraseBranch79(listOf("markdown", "md 格式", "代码块", "fenced"), listOf("支持 Markdown 渲染，注意管理员开关。", "代码块用三个反引号包起来。", "Markdown looks great in Maodouchat.")),
    PhraseBranch79(listOf("正在输入", "typing", "输入中", "对方在打字"), listOf("对方输入状态可关，保护隐私。", "我看到你在打字了。", "Typing indicators are optional.")),
    PhraseBranch79(listOf("禁忌词", "taboo", "闪电回合", "两词故事"), listOf("来局禁忌词描述吧！", "闪电回合，十秒开抢。", "Two-word story time?")),
    PhraseBranch79(listOf("已读", "read receipt", "双勾", "seen"), listOf("已读回执可在后台关闭。", "我这边已读了。", "Read receipts are privacy-gated.")),
    PhraseBranch79(listOf("在线", "presence", "last seen", "最后在线"), listOf("在线状态也可关闭，更私密。", "我现在在线。", "Presence is optional.")),
    PhraseBranch79(listOf("悄悄话", "whisper", "倒计时抢答", "表情对决"), listOf("来局悄悄话挑战！", "倒计时抢答开始。", "Emoji duel — pick a side!")),
    PhraseBranch79(listOf("星标", "star message", "收藏消息", "bookmark"), listOf("重要消息可以星标。", "星标列表稍后一起看。", "I'll star that for later.")),
    PhraseBranch79(listOf("导出聊天", "export chat", "备份聊天", "export history"), listOf("导出前注意密聊限制。", "管理员可关闭导出。", "Export is privacy-gated.")),
    PhraseBranch79(listOf("地理猜猜", "geo guess", "表情记忆", "极速报菜名"), listOf("来局地理猜猜！", "表情记忆，看谁记得牢。", "Rapid fire — go!")),
    PhraseBranch79(listOf("转发", "forward", "转给", "share message"), listOf("转发注意密聊限制。", "管理员可关闭转发。", "Forwarding is privacy-gated.")),
    PhraseBranch79(listOf("全局搜索", "global search", "搜聊天记录", "search chats"), listOf("全局搜索可在后台关闭。", "我帮你关键词定位。", "Search is optional.")),
    PhraseBranch79(listOf("一词接龙", "极速心算", "故事种子", "one word"), listOf("来局一词接龙！", "极速心算，看谁快。", "Story seed — your line!")),
    PhraseBranch79(listOf("加好友", "好友申请", "friend request", "加个好友"), listOf("我发了好友申请。", "好友申请可后台关闭。", "Friend request sent.")),
    PhraseBranch79(listOf("文件夹", "会话分组", "chat folder", "整理会话"), listOf("可以用文件夹整理会话。", "文件夹功能可后台关闭。", "Folders keep chats tidy.")),
    PhraseBranch79(listOf("纯表情", "盲抽", "二选一加强", "emoji only"), listOf("来局纯表情挑战！", "盲抽表情，猜猜是啥。", "Would you rather — round 2!")),
)

private val OFFLINE_FALLBACK79 = listOf(
    "收到，我晚点仔细回你。",
    "明白了。",
    "嗯嗯，继续说。"
)

private fun offlineHasOracle(lower: String, seed: String, keys: List<String>): Boolean {
    for (key in keys) {
        if (containsOracle(lower, lowercaseOracle(key)) || containsOracle(seed, key)) return true
    }
    return false
}

private fun matchBranchesOracle(lower: String, seed: String, branches: List<PhraseBranch79>): List<String>? {
    for (b in branches) {
        val keys = b.keys ?: continue
        if (offlineHasOracle(lower, seed, keys)) return b.replies
    }
    return null
}

private fun matchGreetingOracle(lower: String, seed: String): List<String>? {
    if (containsOracle(seed, "?") || containsOracle(seed, "？") ||
        startsWithOracle(lower, "why") || startsWithOracle(lower, "how") ||
        containsOracle(seed, "吗") || containsOracle(seed, "么") || containsOracle(seed, "呢")
    ) return GREETING_BRANCHES79[0].replies
    return matchBranchesOracle(lower, seed, GREETING_BRANCHES79.subList(1, GREETING_BRANCHES79.size))
}

private fun matchLifeOracle(lower: String, seed: String): List<String>? =
    matchBranchesOracle(lower, seed, LIFE_BRANCHES79)

private fun matchPlayOracle(lower: String, seed: String): List<String>? =
    matchBranchesOracle(lower, seed, PLAY_BRANCHES79)

private fun suggestOracle(lower: String, seed: String, tone: String): List<String> {
    val base = matchGreetingOracle(lower, seed)
        ?: matchLifeOracle(lower, seed)
        ?: matchPlayOracle(lower, seed)
        ?: OFFLINE_FALLBACK79
    val toned = when (tone) {
        "formal" -> base.map { replaceLiteralOracle(replaceLiteralOracle(it, "～", "。"), "嗯嗯，", "好的，") }
        "concise" -> base.map { takeOracle(it, 14) }
        "humorous" -> base.map { it + " :)" }
        "warm" -> base.map { if (endsWithOracle(it, "。") || endsWithOracle(it, "～")) it else it + "～" }
        else -> base
    }
    return distinctOracle(toned).take(4)
}

private val OFFLINE_KEY_POOL79 = listOf(
    "谢谢",
    "thanks",
    "thank",
    "你好",
    "hello",
    "hi ",
    "在吗",
    "在不在",
    "约",
    "见面",
    "吃饭",
    "电影",
    "难过",
    "伤心",
    "累",
    "压力",
    "ok",
    "okay",
    "good night",
    "gn",
    "晚安",
    "好的",
    "行",
    "sorry",
    "抱歉",
    "不好意思",
    "对不起",
    "love",
    "喜欢",
    "爱你",
    "么么",
    "meeting",
    "会议",
    "开会",
    "sync",
    "price",
    "多少钱",
    "费用",
    "报价",
    "photo",
    "图片",
    "照片",
    "看看",
    "code",
    "bug",
    "报错",
    "崩溃",
    "error",
    "weather",
    "天气",
    "下雨",
    "温度",
    "deadline",
    "截止",
    "ddl",
    "明天交",
    "travel",
    "出差",
    "高铁",
    "飞机",
    "酒店",
    "health",
    "感冒",
    "生病",
    "医院",
    "吃药",
    "weekend",
    "周末",
    "假期",
    "vacation",
    "holiday",
    "congrats",
    "恭喜",
    "祝贺",
    "庆祝",
    "offer",
    "traffic",
    "堵车",
    "迟到",
    "晚到",
    "delay",
    "food",
    "吃饭",
    "外卖",
    "餐厅",
    "lunch",
    "dinner",
    "game",
    "游戏",
    "开黑",
    "上分",
    "match",
    "movie",
    "电影",
    "剧",
    "追剧",
    "netflix",
    "work",
    "加班",
    " ent",
    "项目",
    "deadline",
    "money",
    "转账",
    "付款",
    "账单",
    "pay",
    "birthday",
    "生日快乐",
    "过生",
    "study",
    "学习",
    "考试",
    "exam",
    "homework",
    "sport",
    "跑步",
    "健身",
    "gym",
    "workout",
    "music",
    "歌",
    "playlist",
    "演唱会",
    "pet",
    "猫",
    "狗",
    "铲屎",
    "puppy",
    "kitty",
    "secret",
    "密聊",
    "加密",
    "e2ee",
    "sleep",
    "失眠",
    "困",
    "熬夜",
    "insomnia",
    "coffee",
    "咖啡",
    "奶茶",
    "tea",
    "rain",
    "下雪",
    "台风",
    "storm",
    "meeting cancel",
    "取消",
    "改期",
    "reschedule",
    "flight",
    "航班",
    "高铁",
    "train",
    "airport",
    "wifi",
    "网络",
    "断网",
    "lag",
    "卡顿",
    "gift",
    "礼物",
    "惊喜",
    "present",
    "interview",
    "面试",
    "offer",
    "hr",
    "battery",
    "没电",
    "充电",
    "low battery",
    "map",
    "迷路",
    "导航",
    "lost",
    "directions",
    "package",
    "快递",
    "外卖到了",
    "delivery",
    "//",
    "code review",
    "pr ",
    "merge",
    "cook",
    "做饭",
    "菜谱",
    "recipe",
    "plant",
    "浇花",
    "绿植",
    "garden",
    "book",
    "读书",
    "小说",
    "reading",
    "gym fail",
    "没去练",
    "偷懒",
    "rest day",
    "password",
    "密码",
    "2fa",
    "验证码",
    "totp",
    "screenshot",
    "截图",
    "录屏",
    "screen record",
    "budget",
    "预算",
    "省钱",
    "理财",
    "doctor",
    "医院",
    "挂号",
    "clinic",
    "parking",
    "停车",
    "挪车",
    "garage",
    "vpn",
    "代理",
    "翻墙",
    "proxy",
    "backup",
    "备份",
    "导出聊天",
    "export chat",
    "pin message",
    "unpin message",
    "message pin",
    "revoke message",
    "delete message",
    "unsend",
    "pin the mood",
    "revoke rush",
    "secret signal",
    "doc hunt",
    "meaning race",
    "insight sprint",
    "ai file",
    "semantic search",
    "analyze file",
    "pixel quest",
    "assist circle",
    "decision dash",
    "ai analyze",
    "group assistant",
    "image analyze",
    "suggest circle",
    "voice race",
    "reply sprint",
    "ai suggest",
    "ai transcribe",
    "suggest replies",
    "photo race",
    "clip dash",
    "frame hunt",
    "summary circle",
    "rewrite relay",
    "prompt sprint",
    "image send",
    "video send",
    "ai summary",
    "ai rewrite",
    "pin drop",
    "file relay",
    "map dash",
    "vault lock",
    "watermark hunt",
    "secure sprint",
    "secret chat",
    "screen secure",
    "spoiler race",
    "blur battle",
    "download dash",
    "spoiler media",
    "auto download",
    "qr quest",
    "contact swap",
    "scan sprint",
    "qr code",
    "contact card",
    "nudge dash",
    "code check",
    "trust sprint",
    "nudge",
    "safety code",
    "invite race",
    "mention mayhem",
    "link hunt",
    "group invite",
    "mentions",
    "idea relay",
    "tempo tap",
    "translate relay",
    "drafts",
    "ai translate",
    "mood meter",
    "focus sprint",
    "gratitude round",
    "polls",
    "app lock",
    "chat lock",
    "lock chat",
    "pin lock",
    "edit message",
    "message edit",
    "typo fix",
    "code breaker",
    "silly law",
    "emoji math",
    "mute chat",
    "unmute",
    "notifications off",
    "disappear",
    "disappearing",
    "auto delete",
    "阅后即焚",
    "impulse draw",
    "word scramble",
    "reaction duel",
    "pin chat",
    "pinned",
    "unpin",
    "marked unread",
    "mark unread",
    "unread later",
    "mirror echo",
    "sync clap",
    "fact or fiction",
    "archive",
    "archived",
    "inbox",
    "nearby",
    "around me",
    "local people",
    "debate",
    "emoji story",
    "quick poll",
    "moments",
    "posts",
    "timeline",
    "block",
    "report",
    "spam",
    "harass",
    "alphabet",
    "silent movie",
    "color word",
    "sticker",
    "表情包",
    "emoji pack",
    "silent",
    "无声",
    "免打扰",
    "dnd",
    "watermark",
    "盲水印",
    "取证",
    "打电话",
    "call me",
    "视频通话",
    "voice call",
    "facetime",
    "定时",
    "稍后发",
    "schedule",
    "remind me later",
    "群公告",
    "announcement",
    "置顶",
    "pin this",
    "阅后即焚",
    "view once",
    "看完即焚",
    "viewonce",
    "实时位置",
    "live location",
    "共享位置",
    "share location",
    "机器人",
    "bot api",
    "webhook",
    "开发者",
    "markdown",
    "md 格式",
    "代码块",
    "fenced",
    "正在输入",
    "typing",
    "输入中",
    "对方在打字",
    "禁忌词",
    "taboo",
    "闪电回合",
    "两词故事",
    "已读",
    "read receipt",
    "双勾",
    "seen",
    "在线",
    "presence",
    "last seen",
    "最后在线",
    "悄悄话",
    "whisper",
    "倒计时抢答",
    "表情对决",
    "星标",
    "star message",
    "收藏消息",
    "bookmark",
    "导出聊天",
    "export chat",
    "备份聊天",
    "export history",
    "地理猜猜",
    "geo guess",
    "表情记忆",
    "极速报菜名",
    "转发",
    "forward",
    "转给",
    "share message",
    "全局搜索",
    "global search",
    "搜聊天记录",
    "search chats",
    "一词接龙",
    "极速心算",
    "故事种子",
    "one word",
    "加好友",
    "好友申请",
    "friend request",
    "加个好友",
    "文件夹",
    "会话分组",
    "chat folder",
    "整理会话",
    "纯表情",
    "盲抽",
    "二选一加强",
    "emoji only"
)

private val OFFLINE_TONE_POOL79 = listOf(
    "formal", "concise", "humorous", "warm", "friendly", "", "FORMAL",
    "Formal", "formal ", " formal", "concise ", "warm ", "other", "FRIENDLY"
)

private val OFFLINE_TRIGGER_POOL79 = listOf("?", "？", "吗", "么", "呢", "why", "how")

// Random case flip per char (Character primitives only).
private fun randomCaseFlip79(rnd: Random, s: String): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        val c = chars[i]
        if (rnd.nextInt(2) == 0) {
            val up = Character.toUpperCase(c.code)
            if (up != c.code) chars[i] = up.toChar()
        } else {
            val lo = Character.toLowerCase(c.code)
            if (lo != c.code) chars[i] = lo.toChar()
        }
    }
    return String(chars)
}

private fun genOfflineKeyword(rnd: Random): String {
    val key = OFFLINE_KEY_POOL79[rnd.nextInt(OFFLINE_KEY_POOL79.size)]
    return when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> key
        6, 7 -> randomCaseFlip79(rnd, key)
        8 -> randomToken(rnd)
        else -> randomWord(rnd)
    }
}

// lower/seed pair: often embeds a keyword in one side, sometimes a
// question trigger, sometimes pure noise.
private fun genOfflinePair(rnd: Random): Pair<String, String> {
    val shape = rnd.nextInt(10)
    val kw = genOfflineKeyword(rnd)
    val pad = when (rnd.nextInt(4)) {
        0 -> ""
        1 -> " "
        2 -> randomWord(rnd) + " "
        else -> " " + randomWord(rnd)
    }
    val lower = when (shape) {
        0, 1, 2, 3 -> pad + kw
        4 -> kw + pad
        5 -> randomString(rnd, 12)
        6 -> randomToken(rnd)
        7 -> OFFLINE_TRIGGER_POOL79[rnd.nextInt(OFFLINE_TRIGGER_POOL79.size)] + randomWord(rnd)
        8 -> randomWord(rnd) + OFFLINE_TRIGGER_POOL79[rnd.nextInt(OFFLINE_TRIGGER_POOL79.size)]
        else -> randomString(rnd, 24)
    }
    val seed = when (rnd.nextInt(10)) {
        0, 1, 2 -> pad + kw
        3 -> kw + pad
        4 -> randomString(rnd, 12)
        5 -> randomToken(rnd)
        6 -> OFFLINE_TRIGGER_POOL79[rnd.nextInt(OFFLINE_TRIGGER_POOL79.size)] + randomWord(rnd)
        7 -> randomWord(rnd) + OFFLINE_TRIGGER_POOL79[rnd.nextInt(OFFLINE_TRIGGER_POOL79.size)]
        else -> randomString(rnd, 24)
    }
    return lower to seed
}

private fun genOfflineTone(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5, 6, 7 -> OFFLINE_TONE_POOL79[rnd.nextInt(OFFLINE_TONE_POOL79.size)]
        8 -> randomToken(rnd)
        else -> randomString(rnd, 8)
    }

private fun genOfflineKeys(rnd: Random): List<String> {
    val n = rnd.nextInt(0, 5)
    val out = mutableListOf<String>()
    repeat(n) {
        when (rnd.nextInt(10)) {
            0, 1, 2, 3, 4, 5 -> out.add(OFFLINE_KEY_POOL79[rnd.nextInt(OFFLINE_KEY_POOL79.size)])
            6 -> out.add(randomToken(rnd))
            7 -> out.add(randomWord(rnd))
            else -> out.add(randomString(rnd, 8))
        }
    }
    return out
}

    @Test
    fun offlineRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (lower, seed) = genOfflinePair(rnd)
            val tone = genOfflineTone(rnd)
            val keys = genOfflineKeys(rnd)
            assertEquals(suggestOracle(lower, seed, tone), OfflineSuggestionPhrases.suggest(lower, seed, tone))
            assertEquals(matchGreetingOracle(lower, seed), OfflineGreetingPhrases.match(lower, seed))
            assertEquals(matchLifeOracle(lower, seed), OfflineLifePhrases.match(lower, seed))
            assertEquals(matchPlayOracle(lower, seed), OfflinePlayPhrases.match(lower, seed))
            assertEquals(offlineHasOracle(lower, seed, keys), offlineHas(lower, seed, keys))
        }
    }

    @Test
    fun offlineToneRandom() {
        val rnd = Random(7)
        repeat(300) {
            val (lower, seed) = genOfflinePair(rnd)
            val tone = genOfflineTone(rnd)
            assertEquals(suggestOracle(lower, seed, tone), OfflineSuggestionPhrases.suggest(lower, seed, tone))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Greeting question branch wins over keyword branches: "在吗"
        // carries "吗", so it answers the question, never the greeting.
        assertEquals(
            listOf("好问题，我的想法是……", "可以，稍等我整理一下再回你。", "我更倾向这个方向，你觉得呢？"),
            OfflineSuggestionPhrases.suggest("在吗", "在吗", "friendly")
        )
        assertEquals(
            listOf("好问题，我的想法是……", "可以，稍等我整理一下再回你。", "我更倾向这个方向，你觉得呢？"),
            OfflineGreetingPhrases.match("why not", "why not")
        )
        assertEquals(
            listOf("好问题，我的想法是……", "可以，稍等我整理一下再回你。", "我更倾向这个方向，你觉得呢？"),
            OfflineGreetingPhrases.match("how are you", "how are you")
        )
        // "why" must be a prefix of lower: "anyway" does not trigger it.
        assertEquals(null, OfflineGreetingPhrases.match("anyway", "anyway"))
        // Thanks branch, and the pre-existing test's pinned replies.
        assertEquals(
            listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"),
            OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "friendly")
        )
        assertEquals(
            listOf("在的，怎么啦？", "嗨，刚看到～", "在呢，说吧。"),
            OfflineSuggestionPhrases.suggest("你好", "你好", "friendly")
        )
        // Unknown input falls back to the 3 generic replies.
        assertEquals(
            listOf("收到，我晚点仔细回你。", "明白了。", "嗯嗯，继续说。"),
            OfflineSuggestionPhrases.suggest("xyz", "xyz", "friendly")
        )
        // Priority: greeting beats life beats play on the same input.
        // "电影" is a greeting key and a life key; greeting wins.
        assertEquals(
            listOf("时间地点你定，我配合～", "可以啊，周末怎么样？", "我想去，细节再敲定。"),
            OfflineSuggestionPhrases.suggest("电影", "电影", "friendly")
        )
        // "deadline" is a life key twice (deadline branch first, work
        // branch later); the earlier branch wins.
        assertEquals(
            listOf("截止日期我记下了，要不要拆成待办？", "先列三点关键路径，我陪你盯进度。", "需要我帮你写个简短提醒吗？"),
            OfflineSuggestionPhrases.suggest("deadline", "deadline", "friendly")
        )
        // offlineHas: key.lowercase() in lower OR key in seed. Only the
        // KEY is lowercased, never the input on either side: uppercase
        // lower and uppercase seed both miss a lowercase key.
        assertEquals(true, offlineHas("thanks for this", "", listOf("thanks")))
        assertEquals(false, offlineHas("THANKS", "", listOf("thanks")))
        assertEquals(false, offlineHas("", "THANKS", listOf("thanks")))
        assertEquals(true, offlineHas("", "say thanks", listOf("thanks")))
        assertEquals(false, offlineHas("thank", "", listOf("thanks")))
        assertEquals(true, offlineHas("thank", "", listOf("thank")))
        assertEquals(false, offlineHas("", "", listOf("thanks")))
        assertEquals(false, offlineHas("thanks", "", listOf()))
        // Tones: formal swaps "～" -> "。" first, then "嗯嗯，" -> "好的，".
        assertEquals(
            listOf("在的，怎么啦？", "嗨，刚看到。", "在呢，说吧。"),
            OfflineSuggestionPhrases.suggest("你好", "你好", "formal")
        )
        // Formal on the thanks branch: "不客气～" -> "不客气。".
        assertEquals(
            listOf("不客气。", "应该的，有需要再叫我。", "小事一桩。"),
            OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "formal")
        )
        // Concise truncates every reply to 14 chars.
        val concise = OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "concise")
        assertEquals(true, concise.all { it.length <= 14 })
        assertEquals(listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"), concise)
        // Humorous appends " :)" to every reply.
        assertEquals(
            listOf("不客气～ :)", "应该的，有需要再叫我。 :)", "小事一桩。 :)"),
            OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "humorous")
        )
        // Warm appends "～" unless the reply already ends with "。" or "～".
        assertEquals(
            listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"),
            OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "warm")
        )
        // Unknown tone returns the base list unchanged.
        assertEquals(
            listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"),
            OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "weird")
        )
        // distinct() + take(4): the 5- and 6-reply play branches are cut
        // to 4 by suggest, while match() returns the full list.
        val docHunt = OfflinePlayPhrases.match("doc hunt", "")
        assertEquals(5, docHunt!!.size)
        assertEquals(4, OfflineSuggestionPhrases.suggest("doc hunt", "", "friendly").size)
        assertEquals(
            listOf("Doc hunt - find the clause.", "Meaning race - semantic win.", "Insight sprint - one key takeaway.", "AI file analysis is admin-gated."),
            OfflineSuggestionPhrases.suggest("doc hunt", "", "friendly")
        )
        // "photo race" cannot serve here: life's "photo" branch shadows
        // it in suggest(), so use "clip dash" from the same 6-reply branch.
        val clipDash = OfflinePlayPhrases.match("clip dash", "")
        assertEquals(6, clipDash!!.size)
        assertEquals(4, OfflineSuggestionPhrases.suggest("clip dash", "", "friendly").size)
        // Per-branch pins (generated from the production tables):
        assertEquals(listOf("好问题，我的想法是……", "可以，稍等我整理一下再回你。", "我更倾向这个方向，你觉得呢？"), OfflineGreetingPhrases.match("", "?"))
        assertEquals(listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"), OfflineGreetingPhrases.match("谢谢", ""))
        assertEquals(listOf("在的，怎么啦？", "嗨，刚看到～", "在呢，说吧。"), OfflineGreetingPhrases.match("你好", ""))
        assertEquals(listOf("时间地点你定，我配合～", "可以啊，周末怎么样？", "我想去，细节再敲定。"), OfflineGreetingPhrases.match("约", ""))
        assertEquals(listOf("我在这儿听你说。", "辛苦了，先歇一会儿吧。", "需要的话我陪你聊聊。"), OfflineGreetingPhrases.match("难过", ""))
        assertEquals(listOf("好的，收到。", "嗯嗯，晚点聊。", "Alright, talk soon."), OfflineGreetingPhrases.match("ok", ""))
        assertEquals(listOf("没关系。", "理解你，没事的。", "It's okay, no worries."), OfflineGreetingPhrases.match("sorry", ""))
        assertEquals(listOf("我也是～", "收到满满的喜欢。", "同样的感觉。"), OfflineGreetingPhrases.match("love", ""))
        assertEquals(listOf("好的，我改一下时间。", "议程我稍后发你。", "Can we do a short call?"), OfflineLifePhrases.match("meeting", ""))
        assertEquals(listOf("我整理一版报价给你。", "方便说下预算范围吗？", "Let me send options."), OfflineLifePhrases.match("price", ""))
        assertEquals(listOf("发我看看～", "收到，我仔细看下。", "Looks good!"), OfflineLifePhrases.match("photo", ""))
        assertEquals(listOf("日志发我一段。", "我这边复现一下。", "Might be a race; checking."), OfflineLifePhrases.match("code", ""))
        assertEquals(listOf("记得看下天气预报再出门～", "要不要改成室内活动？", "我这边帮你记着关注天气变化。"), OfflineLifePhrases.match("weather", ""))
        assertEquals(listOf("截止日期我记下了，要不要拆成待办？", "先列三点关键路径，我陪你盯进度。", "需要我帮你写个简短提醒吗？"), OfflineLifePhrases.match("deadline", ""))
        assertEquals(listOf("行程我可以帮你整理成清单。", "要不要同步一下出发/到达时间？", "路上注意安全，到了报个平安～"), OfflineLifePhrases.match("travel", ""))
        assertEquals(listOf("多休息喝温水，严重就去看医生。", "需要我帮你请假话术吗？", "好好照顾自己，别硬撑。"), OfflineLifePhrases.match("health", ""))
        assertEquals(listOf("周末有什么安排？", "好好休息，周一见。", "Any fun plans this weekend?"), OfflineLifePhrases.match("weekend", ""))
        assertEquals(listOf("太棒了，恭喜你！", "值得好好庆祝一下。", "Congrats — well earned!"), OfflineLifePhrases.match("congrats", ""))
        assertEquals(listOf("注意安全，到了说一声。", "没关系，我可以等你。", "Safe travels, take your time."), OfflineLifePhrases.match("traffic", ""))
        assertEquals(listOf("要一起点外卖吗？", "我可以帮你列几个选项。", "I'm hungry too — any preference?"), OfflineLifePhrases.match("food", ""))
        assertEquals(listOf("现在开一局？", "我准备好了，你定模式。", "Queue up, I'm in."), OfflineLifePhrases.match("game", ""))
        assertEquals(listOf("有推荐的片单吗？", "今晚一起看？", "Send the title, I'll check."), OfflineLifePhrases.match("movie", ""))
        assertEquals(listOf("进度我记下了，需要帮忙拆任务吗？", "先聚焦最关键的一件事。", "Want a quick status checklist?"), OfflineLifePhrases.match("work", ""))
        assertEquals(listOf("金额确认后我再操作。", "发我账单明细～", "I'll confirm and get back."), OfflineLifePhrases.match("money", ""))
        assertEquals(listOf("生日快乐！今天过得开心点。", "要不要一起安排个小庆祝？", "送你一个虚拟蛋糕"), OfflineLifePhrases.match("birthday", ""))
        assertEquals(listOf("要不要一起复盘一下重点？", "先休息五分钟再继续。", "I can quiz you on the hard parts."), OfflineLifePhrases.match("study", ""))
        assertEquals(listOf("今天练哪一块？", "加油，注意拉伸。", "Send me your PR, I want to cheer."), OfflineLifePhrases.match("sport", ""))
        assertEquals(listOf("发我歌单听听。", "这首循环了好几遍。", "Any new recommendations?"), OfflineLifePhrases.match("music", ""))
        assertEquals(listOf("毛孩子今天乖不乖？", "求吸猫/吸狗现场。", "Pet tax please "), OfflineLifePhrases.match("pet", ""))
        assertEquals(listOf("敏感内容我们用密聊说。", "记得开阅后即焚。", "I'll keep this private."), OfflineLifePhrases.match("secret", ""))
        assertEquals(listOf("早点休息，明天再说。", "我先不打扰你了。", "Sleep well — talk tomorrow."), OfflineLifePhrases.match("sleep", ""))
        assertEquals(listOf("来一杯提神？", "我请你喝。", "Coffee or tea?"), OfflineLifePhrases.match("coffee", ""))
        assertEquals(listOf("出门记得带伞。", "注意安全。", "Stay dry out there."), OfflineLifePhrases.match("rain", ""))
        assertEquals(listOf("那我们另约时间。", "收到，我改日历了。", "No problem — propose a new slot."), OfflineLifePhrases.match("取消", ""))
        assertEquals(listOf("一路顺风，落地报平安。", "需要我帮你看时刻表吗？", "Safe travels — ping me when you land."), OfflineLifePhrases.match("flight", ""))
        assertEquals(listOf("可能是网络波动，稍后再试。", "我这边也有点卡。", "Try switching networks?"), OfflineLifePhrases.match("wifi", ""))
        assertEquals(listOf("要不要一起挑个礼物？", "保密，别剧透～", "I have an idea — call me."), OfflineLifePhrases.match("gift", ""))
        assertEquals(listOf("祝你顺利，稳住发挥。", "需要我帮你过一遍常见问题吗？", "You've got this — knock them out."), OfflineLifePhrases.match("interview", ""))
        assertEquals(listOf("快没电了，我先去充电。", "回头再聊～", "Powering up — brb."), OfflineLifePhrases.match("battery", ""))
        assertEquals(listOf("发我定位，我帮你看。", "别急，先找个地标。", "Share your pin, I'll guide you."), OfflineLifePhrases.match("map", ""))
        assertEquals(listOf("收到了说一声。", "我下楼拿。", "I'll grab it."), OfflineLifePhrases.match("package", ""))
        assertEquals(listOf("我晚点看你的 PR。", "有冲突先 rebase 一下。", "LGTM with nits — shipping."), OfflineLifePhrases.match("//", ""))
        assertEquals(listOf("今晚想吃什么？", "发我菜谱链接～", "I can help plan the menu."), OfflineLifePhrases.match("cook", ""))
        assertEquals(listOf("别忘了浇水。", "新芽发了吗？", "Plant tax photos please."), OfflineLifePhrases.match("plant", ""))
        assertEquals(listOf("最近在看什么？", "读完安利我。", "Drop the title — adding to my list."), OfflineLifePhrases.match("book", ""))
        assertEquals(listOf("休息也是训练的一部分。", "明天补上就好。", "Rest day accepted."), OfflineLifePhrases.match("没去练", ""))
        assertEquals(listOf("别在群里发验证码。", "建议开 TOTP 两步验证。", "Reset via secure channel only."), OfflineLifePhrases.match("password", ""))
        assertEquals(listOf("密聊请勿截图。", "有盲水印可追溯。", "Use view-once if sensitive."), OfflineLifePhrases.match("screenshot", ""))
        assertEquals(listOf("我们列个简单预算表。", "先区分必要与可选开支。", "Want a 3-line budget?"), OfflineLifePhrases.match("budget", ""))
        assertEquals(listOf("早去排队，记得带证件。", "需要我陪你吗？", "Feel better soon."), OfflineLifePhrases.match("doctor", ""))
        assertEquals(listOf("我马上挪一下。", "发我位置。", "On my way to move it."), OfflineLifePhrases.match("parking", ""))
        assertEquals(listOf("注意账号安全，别分享节点。", "优先用官方通道。", "Keep credentials private."), OfflineLifePhrases.match("vpn", ""))
        assertEquals(listOf("密聊内容不建议明文导出。", "可用加密备份方案。", "Prefer encrypted backups only."), OfflinePlayPhrases.match("backup", ""))
        assertEquals(listOf("Pinned for the group.", "Unpinned — no longer sticky.", "Pin the important update."), OfflinePlayPhrases.match("pin message", ""))
        assertEquals(listOf("Revoked — pretend you didn't see it.", "Deleted on my side.", "I'll unsend that."), OfflinePlayPhrases.match("revoke message", ""))
        assertEquals(listOf("Pin the mood for today.", "Revoke rush starts now.", "Secret signal received."), OfflinePlayPhrases.match("pin the mood", ""))
        assertEquals(listOf("Doc hunt - find the clause.", "Meaning race - semantic win.", "Insight sprint - one key takeaway.", "AI file analysis is admin-gated.", "Semantic search can be limited."), OfflinePlayPhrases.match("doc hunt", ""))
        assertEquals(listOf("Pixel quest - find the clue in the photo.", "Assist circle - group AI recap.", "Decision dash - pick next steps.", "AI image analysis is admin-gated.", "Group assistant can be limited."), OfflinePlayPhrases.match("pixel quest", ""))
        assertEquals(listOf("Suggest circle - share a quick reply idea.", "Voice race - short clear note.", "Reply sprint - three options fast.", "AI suggest replies is admin-gated.", "AI transcribe can be limited."), OfflinePlayPhrases.match("suggest circle", ""))
        assertEquals(listOf("Photo race - first clear snap.", "Clip dash - short video win.", "Frame hunt - find the detail.", "Summary circle - one-line recap.", "Rewrite relay - polish the draft.", "Prompt sprint - ask better."), OfflinePlayPhrases.match("photo race", ""))
        assertEquals(listOf("Pin drop - share a static pin.", "File relay - pass the document.", "Map dash - race the route.", "Vault lock - secret chat on.", "Watermark hunt - find the mark.", "Secure sprint - FLAG_SECURE active."), OfflinePlayPhrases.match("pin drop", ""))
        assertEquals(listOf("Spoiler race - no peeking.", "Blur battle - guess the shot.", "Download dash - save on wifi.", "Spoiler media is admin-gated.", "Auto-download can be limited."), OfflinePlayPhrases.match("spoiler race", ""))
        assertEquals(listOf("QR quest - frame and scan.", "Contact swap - share cards carefully.", "Scan sprint - steady hands.", "QR codes stay optional.", "Contact cards are admin-gated."), OfflinePlayPhrases.match("qr quest", ""))
        assertEquals(listOf("Nudge dash - double-tap race.", "Code check - compare digits offline.", "Trust sprint - verify safety code.", "Nudge is a light poke, not a call.", "Safety codes stay on-device."), OfflinePlayPhrases.match("nudge dash", ""))
        assertEquals(listOf("Invite race starts now.", "Mention mayhem - tag carefully.", "Link hunt - find the clue.", "Invite link is ready.", "Mentions stay private in E2EE."), OfflinePlayPhrases.match("invite race", ""))
        assertEquals(listOf("Idea relay - pass one idea.", "Tempo tap - keep the beat.", "Translate relay - next language.", "Draft saved on this device.", "AI translate is ready."), OfflinePlayPhrases.match("idea relay", ""))
        assertEquals(listOf("Rate the mood meter 1-10.", "Focus sprint - set a timer.", "Gratitude round: one win.", "Quick poll is ready.", "App lock keeps the session private."), OfflinePlayPhrases.match("mood meter", ""))
        assertEquals(listOf("Chat lock is on — enter PIN.", "Unlock when you're ready.", "Keep the lock PIN private."), OfflinePlayPhrases.match("chat lock", ""))
        assertEquals(listOf("Edited — fixed the typo.", "Edit window is short, act fast.", "I'll edit that message."), OfflinePlayPhrases.match("edit message", ""))
        assertEquals(listOf("Code breaker — four digits.", "New silly law for the group.", "Emoji math — solve it."), OfflinePlayPhrases.match("code breaker", ""))
        assertEquals(listOf("Muted this chat for focus.", "Unmute when free.", "Silence is intentional."), OfflinePlayPhrases.match("mute chat", ""))
        assertEquals(listOf("Timer is set — messages will vanish.", "Use a short timer for sensitive stuff.", "Disappearing messages keep history light."), OfflinePlayPhrases.match("disappear", ""))
        assertEquals(listOf("Impulse draw — lucky you?", "Unscramble this word.", "Reaction duel — pick a side."), OfflinePlayPhrases.match("impulse draw", ""))
        assertEquals(listOf("Pinned so I don't lose it.", "Unpin when done.", "Pin the important thread."), OfflinePlayPhrases.match("pin chat", ""))
        assertEquals(listOf("Marked unread for later.", "I'll clear it when I finish.", "Unread badge is intentional."), OfflinePlayPhrases.match("marked unread", ""))
        assertEquals(listOf("Mirror echo — reverse me.", "Sync clap on three.", "Fact or fiction — guess!"), OfflinePlayPhrases.match("mirror echo", ""))
        assertEquals(listOf("Archived — ping if urgent.", "I'll unarchive later.", "Inbox zero-ish after archive."), OfflinePlayPhrases.match("archive", ""))
        assertEquals(listOf("Nearby is optional privacy-wise.", "Turn radius down if crowded.", "Sharing location only when needed."), OfflinePlayPhrases.match("nearby", ""))
        assertEquals(listOf("Debate flash — pick a side.", "Emoji story round!", "Quick poll — vote now."), OfflinePlayPhrases.match("debate", ""))
        assertEquals(listOf("Check my latest post when free.", "Moments can wait — chatting first.", "I just shared something on Moments."), OfflinePlayPhrases.match("moments", ""))
        assertEquals(listOf("You can block or report if needed.", "Safety first — don't tolerate abuse.", "I can help you report this."), OfflinePlayPhrases.match("block", ""))
        assertEquals(listOf("Alphabet race — your turn.", "Silent movie round next.", "Color-word challenge accepted."), OfflinePlayPhrases.match("alphabet", ""))
        assertEquals(listOf("发一个合适的贴纸？", "这个表情包绝了。", "Sticker energy."), OfflinePlayPhrases.match("sticker", ""))
        assertEquals(listOf("我用无声发送，不吵你。", "先免打扰，晚点聊。", "Sending silently."), OfflinePlayPhrases.match("silent", ""))
        assertEquals(listOf("截图会有盲水印痕迹。", "后台可提取水印信息。", "Forensics-ready."), OfflinePlayPhrases.match("watermark", ""))
        assertEquals(listOf("我现在方便接电话。", "改文字聊也可以。", "Want a quick call?"), OfflinePlayPhrases.match("打电话", ""))
        assertEquals(listOf("我设个定时消息。", "到点我提醒你。", "I'll schedule it."), OfflinePlayPhrases.match("定时", ""))
        assertEquals(listOf("建议置顶关键信息。", "我来发一版群公告草稿。", "Pin the summary?"), OfflinePlayPhrases.match("群公告", ""))
        assertEquals(listOf("敏感图用阅后即焚发。", "看完就没了，注意隐私。", "Sending as view-once."), OfflinePlayPhrases.match("view once", ""))
        assertEquals(listOf("我开了实时位置，到了关。", "只共享一会儿。", "Sharing live location briefly."), OfflinePlayPhrases.match("实时位置", ""))
        assertEquals(listOf("可以自助接入机器人。", "Webhook 记得验签。", "Check the bot developer docs."), OfflinePlayPhrases.match("机器人", ""))
        assertEquals(listOf("支持 Markdown 渲染，注意管理员开关。", "代码块用三个反引号包起来。", "Markdown looks great in Maodouchat."), OfflinePlayPhrases.match("markdown", ""))
        assertEquals(listOf("对方输入状态可关，保护隐私。", "我看到你在打字了。", "Typing indicators are optional."), OfflinePlayPhrases.match("正在输入", ""))
        assertEquals(listOf("来局禁忌词描述吧！", "闪电回合，十秒开抢。", "Two-word story time?"), OfflinePlayPhrases.match("禁忌词", ""))
        assertEquals(listOf("已读回执可在后台关闭。", "我这边已读了。", "Read receipts are privacy-gated."), OfflinePlayPhrases.match("已读", ""))
        assertEquals(listOf("在线状态也可关闭，更私密。", "我现在在线。", "Presence is optional."), OfflinePlayPhrases.match("在线", ""))
        assertEquals(listOf("来局悄悄话挑战！", "倒计时抢答开始。", "Emoji duel — pick a side!"), OfflinePlayPhrases.match("悄悄话", ""))
        assertEquals(listOf("重要消息可以星标。", "星标列表稍后一起看。", "I'll star that for later."), OfflinePlayPhrases.match("星标", ""))
        assertEquals(listOf("导出前注意密聊限制。", "管理员可关闭导出。", "Export is privacy-gated."), OfflinePlayPhrases.match("export history", ""))
        assertEquals(listOf("来局地理猜猜！", "表情记忆，看谁记得牢。", "Rapid fire — go!"), OfflinePlayPhrases.match("地理猜猜", ""))
        assertEquals(listOf("转发注意密聊限制。", "管理员可关闭转发。", "Forwarding is privacy-gated."), OfflinePlayPhrases.match("转发", ""))
        assertEquals(listOf("全局搜索可在后台关闭。", "我帮你关键词定位。", "Search is optional."), OfflinePlayPhrases.match("全局搜索", ""))
        assertEquals(listOf("来局一词接龙！", "极速心算，看谁快。", "Story seed — your line!"), OfflinePlayPhrases.match("一词接龙", ""))
        assertEquals(listOf("我发了好友申请。", "好友申请可后台关闭。", "Friend request sent."), OfflinePlayPhrases.match("加好友", ""))
        assertEquals(listOf("可以用文件夹整理会话。", "文件夹功能可后台关闭。", "Folders keep chats tidy."), OfflinePlayPhrases.match("文件夹", ""))
        assertEquals(listOf("来局纯表情挑战！", "盲抽表情，猜猜是啥。", "Would you rather — round 2!"), OfflinePlayPhrases.match("纯表情", ""))
        // suggest() first reply per branch (tone "friendly"):
        val firstReplyCases = listOf(
            Triple("greeting", "?", "好问题，我的想法是……"),
            Triple("greeting", "谢谢", "不客气～"),
            Triple("greeting", "你好", "在的，怎么啦？"),
            Triple("greeting", "约", "时间地点你定，我配合～"),
            Triple("greeting", "难过", "我在这儿听你说。"),
            Triple("greeting", "ok", "好的，收到。"),
            Triple("greeting", "sorry", "没关系。"),
            Triple("greeting", "love", "我也是～"),
            Triple("life", "meeting", "好的，我改一下时间。"),
            Triple("life", "price", "我整理一版报价给你。"),
            Triple("life", "photo", "发我看看～"),
            Triple("life", "code", "日志发我一段。"),
            Triple("life", "weather", "记得看下天气预报再出门～"),
            Triple("life", "deadline", "截止日期我记下了，要不要拆成待办？"),
            Triple("life", "travel", "行程我可以帮你整理成清单。"),
            Triple("life", "health", "多休息喝温水，严重就去看医生。"),
            Triple("life", "weekend", "周末有什么安排？"),
            Triple("life", "congrats", "太棒了，恭喜你！"),
            Triple("life", "traffic", "注意安全，到了说一声。"),
            Triple("life", "food", "要一起点外卖吗？"),
            Triple("life", "game", "现在开一局？"),
            Triple("life", "movie", "有推荐的片单吗？"),
            Triple("life", "work", "进度我记下了，需要帮忙拆任务吗？"),
            Triple("life", "money", "金额确认后我再操作。"),
            Triple("life", "birthday", "生日快乐！今天过得开心点。"),
            Triple("life", "study", "要不要一起复盘一下重点？"),
            Triple("life", "sport", "今天练哪一块？"),
            Triple("life", "music", "发我歌单听听。"),
            Triple("life", "pet", "毛孩子今天乖不乖？"),
            Triple("life", "secret", "敏感内容我们用密聊说。"),
            Triple("life", "sleep", "早点休息，明天再说。"),
            Triple("life", "coffee", "来一杯提神？"),
            Triple("life", "rain", "出门记得带伞。"),
            Triple("life", "取消", "那我们另约时间。"),
            Triple("life", "flight", "一路顺风，落地报平安。"),
            Triple("life", "wifi", "可能是网络波动，稍后再试。"),
            Triple("life", "gift", "要不要一起挑个礼物？"),
            Triple("life", "interview", "祝你顺利，稳住发挥。"),
            Triple("life", "battery", "快没电了，我先去充电。"),
            Triple("life", "map", "发我定位，我帮你看。"),
            Triple("life", "package", "收到了说一声。"),
            Triple("life", "//", "我晚点看你的 PR。"),
            Triple("life", "做饭", "今晚想吃什么？"),
            Triple("life", "plant", "别忘了浇水。"),
            Triple("life", "读书", "最近在看什么？"),
            Triple("life", "没去练", "休息也是训练的一部分。"),
            Triple("life", "password", "别在群里发验证码。"),
            Triple("life", "screenshot", "密聊请勿截图。"),
            Triple("life", "budget", "我们列个简单预算表。"),
            Triple("life", "doctor", "早去排队，记得带证件。"),
            Triple("life", "parking", "我马上挪一下。"),
            Triple("life", "vpn", "注意账号安全，别分享节点。"),
            Triple("play", "backup", "密聊内容不建议明文导出。"),
            Triple("play", "pin message", "Pinned for the group."),
            Triple("play", "delete message", "Revoked — pretend you didn't see it."),
            Triple("play", "pin the mood", "Pin the mood for today."),
            Triple("play", "doc hunt", "Doc hunt - find the clause."),
            Triple("play", "pixel quest", "Pixel quest - find the clue in the photo."),
            Triple("play", "suggest circle", "Suggest circle - share a quick reply idea."),
            Triple("play", "clip dash", "Photo race - first clear snap."),
            Triple("play", "pin drop", "Pin drop - share a static pin."),
            Triple("play", "spoiler race", "Spoiler race - no peeking."),
            Triple("play", "qr quest", "QR quest - frame and scan."),
            Triple("play", "nudge dash", "Nudge dash - double-tap race."),
            Triple("play", "invite race", "Invite race starts now."),
            Triple("play", "idea relay", "Idea relay - pass one idea."),
            Triple("play", "mood meter", "Rate the mood meter 1-10."),
            Triple("play", "chat lock", "Chat lock is on — enter PIN."),
            Triple("play", "edit message", "Edited — fixed the typo."),
            Triple("play", "silly law", "Code breaker — four digits."),
            Triple("play", "mute chat", "Muted this chat for focus."),
            Triple("play", "disappear", "Timer is set — messages will vanish."),
            Triple("play", "impulse draw", "Impulse draw — lucky you?"),
            Triple("play", "pin chat", "Pinned so I don't lose it."),
            Triple("play", "marked unread", "Marked unread for later."),
            Triple("play", "mirror echo", "Mirror echo — reverse me."),
            Triple("play", "archive", "Archived — ping if urgent."),
            Triple("play", "nearby", "Nearby is optional privacy-wise."),
            Triple("play", "debate", "Debate flash — pick a side."),
            Triple("play", "moments", "Check my latest post when free."),
            Triple("play", "block", "You can block or report if needed."),
            Triple("play", "alphabet", "Alphabet race — your turn."),
            Triple("play", "sticker", "发一个合适的贴纸？"),
            Triple("play", "silent", "我用无声发送，不吵你。"),
            Triple("play", "watermark", "截图会有盲水印痕迹。"),
            Triple("play", "打电话", "我现在方便接电话。"),
            Triple("play", "定时", "我设个定时消息。"),
            Triple("play", "群公告", "建议置顶关键信息。"),
            Triple("play", "view once", "敏感图用阅后即焚发。"),
            Triple("play", "实时位置", "我开了实时位置，到了关。"),
            Triple("play", "机器人", "可以自助接入机器人。"),
            Triple("play", "markdown", "支持 Markdown 渲染，注意管理员开关。"),
            Triple("play", "正在输入", "对方输入状态可关，保护隐私。"),
            Triple("play", "禁忌词", "来局禁忌词描述吧！"),
            Triple("play", "已读", "已读回执可在后台关闭。"),
            Triple("play", "在线", "在线状态也可关闭，更私密。"),
            Triple("play", "悄悄话", "来局悄悄话挑战！"),
            Triple("play", "星标", "重要消息可以星标。"),
            Triple("play", "export history", "导出前注意密聊限制。"),
            Triple("play", "地理猜猜", "来局地理猜猜！"),
            Triple("play", "转发", "转发注意密聊限制。"),
            Triple("play", "全局搜索", "全局搜索可在后台关闭。"),
            Triple("play", "一词接龙", "来局一词接龙！"),
            Triple("play", "加好友", "我发了好友申请。"),
            Triple("play", "文件夹", "可以用文件夹整理会话。"),
            Triple("play", "纯表情", "来局纯表情挑战！"),
        )
        for ((cluster, key, first) in firstReplyCases) {
            val seed = if (cluster == "greeting" && key == "?") "?" else ""
            val lower = if (cluster == "greeting" && key == "?") "" else key
            assertEquals(first, OfflineSuggestionPhrases.suggest(lower, seed, "friendly").first())
        }
    }
}
