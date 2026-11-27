package com.maodouchat.server.bot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------- oracle ----------

// Java regex \s: space, tab, newline, vertical tab, form feed, carriage return.
private fun isAsciiWs188(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

// Kotlin trim(): drops leading/trailing Char.isWhitespace() chars.
// Measured on kotlinc 2.4.0 (TrimProbe2): 0x09-0x0D, 0x1C-0x20, 0x00A0,
// 0x1680, 0x2000-0x200A, 0x2028-0x2029, 0x202F, 0x205F, 0x3000 --
// 28 code points. U+0085 and U+FEFF are NOT trimmed, NBSP IS.
private fun ktTrim188(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i].isWhitespace()) i++
    while (j > i && s[j - 1].isWhitespace()) j--
    return s.substring(i, j)
}

private fun isCmdStart188(c: Char): Boolean = c in 'a'..'z'
private fun isCmdRest188(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '_'

internal fun looksLikeCiphertextOracle188(content: String): Boolean {
    val t = ktTrim188(content)
    if (t.isEmpty()) return false
    if (t.startsWith("SK:") || t.startsWith("ENC:")) return true
    if (t.contains("\"ciphertext\"") || t.contains("\"senderKey")) return true
    return false
}

internal fun sanitizeInboxTextOracle188(raw: String): String? {
    val text = ktTrim188(raw).take(4000)
    if (text.isEmpty()) return null
    if (looksLikeCiphertextOracle188(text)) return null
    return text
}

// Manual scan of ^/([a-z][a-z0-9_]{0,31})(?:@([a-z][a-z0-9_]{2,31}))?(?:\s+([\s\S]*))?$
internal fun parseSlashOracle188(text: String): SlashCommand? {
    val t = ktTrim188(text)
    if (!t.startsWith("/")) return null
    var i = 1
    if (i >= t.length || !isCmdStart188(t[i])) return null
    val cmdStart = i
    i++
    while (i < t.length && isCmdRest188(t[i]) && i - cmdStart < 32) i++
    if (i < t.length && isCmdRest188(t[i])) return null
    val command = t.substring(cmdStart, i)
    var username: String? = null
    if (i < t.length && t[i] == '@') {
        val uStart = i + 1
        if (uStart >= t.length || !isCmdStart188(t[uStart])) return null
        var j = uStart + 1
        while (j < t.length && isCmdRest188(t[j]) && j - uStart < 32) j++
        if (j - uStart < 3) return null
        if (j < t.length && isCmdRest188(t[j])) return null
        username = t.substring(uStart, j)
        i = j
    }
    val arguments = if (i >= t.length) "" else {
        if (!isAsciiWs188(t[i])) return null
        var j = i
        while (j < t.length && isAsciiWs188(t[j])) j++
        ktTrim188(t.substring(j))
    }
    return SlashCommand(command = command, targetUsername = username, arguments = arguments)
}

// Manual scan of ^@([a-z][a-z0-9_]{2,31})(?:\s+([\s\S]*))?$
internal fun parseMentionOracle188(text: String): MentionCommand? {
    val t = ktTrim188(text)
    if (!t.startsWith("@")) return null
    val uStart = 1
    if (uStart >= t.length || !isCmdStart188(t[uStart])) return null
    var j = uStart + 1
    while (j < t.length && isCmdRest188(t[j]) && j - uStart < 32) j++
    if (j - uStart < 3) return null
    if (j < t.length && isCmdRest188(t[j])) return null
    val username = t.substring(uStart, j)
    val rest = if (j >= t.length) "" else {
        if (!isAsciiWs188(t[j])) return null
        var k = j
        while (k < t.length && isAsciiWs188(t[k])) k++
        ktTrim188(t.substring(k))
    }
    return MentionCommand(username = username, text = rest)
}

internal fun shouldAcceptInboxOracle188(text: String, isDirectWithBot: Boolean): Boolean {
    val cleaned = sanitizeInboxTextOracle188(text) ?: return false
    if (isDirectWithBot) return true
    return parseSlashOracle188(cleaned) != null || parseMentionOracle188(cleaned) != null
}

internal data class BotCase188(val text: String, val direct: Boolean)

internal fun botResultWireJson188(c: BotCase188): String {
    val cipher = looksLikeCiphertextOracle188(c.text)
    val san = sanitizeInboxTextOracle188(c.text)
    val slash = parseSlashOracle188(c.text)
    val mention = parseMentionOracle188(c.text)
    val accept = shouldAcceptInboxOracle188(c.text, c.direct)
    return buildString {
        append(if (cipher) "1" else "0")
        append("|")
        append(san?.let { "S" + hexOf188(it) } ?: "N")
        append("|")
        append(slash?.let { "S" + it.command + "," + (it.targetUsername ?: "") + "," + hexOf188(it.arguments) } ?: "N")
        append("|")
        append(mention?.let { "M" + it.username + "," + hexOf188(it.text) } ?: "N")
        append("|")
        append(if (accept) "1" else "0")
    }
}

internal fun hexOf188(s: String): String =
    s.map { "%04x".format(it.code) }.joinToString("")

internal fun botCaseWireJson188(c: BotCase188): String =
    hexOf188(c.text) + "|" + if (c.direct) "1" else "0"

// ---------- generators ----------

internal val BOT_CMDS188 = listOf(
    "", "a", "start", "help", "cmd", "ping", "a".repeat(31), "a".repeat(32),
    "a".repeat(33), "UPPER", "1abc", "a-b", "a.b", "a b", "cmd_name_123", "_x", "x_",
)
internal val BOT_USERS188 = listOf(
    "", "b", "ab", "bot", "bot_name_123", "a".repeat(30), "a".repeat(31),
    "a".repeat(32), "a".repeat(33), "UPPER", "a-b", "a.b", "x_",
)
internal val BOT_WS188 = listOf(" ", "  ", "\t", "\n", "\r", "\u000B", "\u000C", "\u00A0", "\u2003", "\u2028")
internal val BOT_ARGS188 = listOf(
    "", "x", "arg1 arg2", "tomorrow", "  spaced  ", "tab\there", "line\nbreak",
    "\uD83D\uDD11emoji", "a".repeat(100), "a b c", "@someone", "/other",
)
internal val BOT_CIPHER188 = listOf(
    "SK:", "ENC:", "{\"ciphertext\":\"x\"}", "has \"senderKey\" inside",
    "sk:", "enc:", "SK", "ENC", "SK:deadbeef", "  ENC:xyz  ",
)

internal fun randomBotText188(r: Random): String {
    return when (r.nextInt(10)) {
        0 -> BOT_CIPHER188.random(r) + BOT_ARGS188.random(r)
        1 -> BOT_CIPHER188.random(r)
        2 -> {
            val head = listOf("/", "@", "", "x", "/ ", "@ ", "//", "@@").random(r)
            val cmd = BOT_CMDS188.random(r)
            val user = if (r.nextBoolean()) "@" + BOT_USERS188.random(r) else ""
            val ws = if (r.nextBoolean()) BOT_WS188.random(r) else ""
            val args = if (r.nextBoolean()) BOT_ARGS188.random(r) else ""
            head + cmd + user + ws + args
        }
        3 -> {
            // Long text straddling the 4000-unit take() cut, sometimes with an astral char on the cut.
            val pad = "x".repeat(r.nextInt(3990, 4012))
            val tail = if (r.nextBoolean()) "\uD83D\uDD11" + "y".repeat(r.nextInt(0, 10)) else "y".repeat(r.nextInt(0, 10))
            pad + tail
        }
        4 -> BOT_WS188.random(r) + BOT_CMDS188.random(r) + BOT_WS188.random(r)
        5 -> listOf("hello everyone", "hi", "  ", "", "\u00A0", "\uD83D\uDE00", "bot_123", "bot_x").random(r)
        6 -> "/" + BOT_CMDS188.random(r) + "@" + BOT_USERS188.random(r) + BOT_WS188.random(r) + BOT_ARGS188.random(r)
        7 -> "@" + BOT_USERS188.random(r) + BOT_WS188.random(r) + BOT_ARGS188.random(r)
        8 -> "x".repeat(r.nextInt(0, 50)) + BOT_CIPHER188.random(r) + "x".repeat(r.nextInt(0, 20))
        else -> {
            val n = r.nextInt(1, 5)
            buildString {
                repeat(n) {
                    append(listOf("/", "@", "a", "Z", "0", "_", "-", " ", "\t", ":", "\"", "{", "}", "\u00A0", "\uD83D\uDE00").random(r))
                }
            }
        }
    }
}

internal fun randomBotCase188(r: Random): BotCase188 =
    BotCase188(randomBotText188(r), r.nextBoolean())

internal val BOT_PINNED188: List<BotCase188> = listOf(
    BotCase188("", false), BotCase188("", true),
    BotCase188("   ", false), BotCase188("\u00A0", false),
    BotCase188("/start", false), BotCase188("/start", true),
    BotCase188("/help@weather_bot tomorrow", false),
    BotCase188("/ping", false), BotCase188("/ping ", false),
    BotCase188("/cmd  double  space  ", false),
    BotCase188("/cmd\ttab", false), BotCase188("/cmd\nline", false),
    BotCase188("/cmd\u00A0 x", false), BotCase188("\u00A0/start\u00A0", false),
    BotCase188("/UPPER", false), BotCase188("/1abc", false),
    BotCase188("/a@b", false), BotCase188("/a@bcd", false),
    BotCase188("/a@bcd x y", false), BotCase188("/cmd@bot", false),
    BotCase188("/cmd@" + "a".repeat(33), false),
    BotCase188("/" + "a".repeat(33), false),
    BotCase188("/" + "a".repeat(32), false),
    BotCase188("/" + "a".repeat(32) + "@" + "b".repeat(32), false),
    BotCase188("@helper_bot 查天气", false),
    BotCase188("@ab hi", false), BotCase188("@abc", false),
    BotCase188("@" + "a".repeat(32), false), BotCase188("@" + "a".repeat(33), false),
    BotCase188("@UPPER_bot x", false), BotCase188("@1abc x", false),
    BotCase188("SK:deadbeef", true), BotCase188("SK:deadbeef", false),
    BotCase188("{\"ciphertext\":\"abc\"}", true),
    BotCase188("has \"senderKey\" inside", false),
    BotCase188("hello everyone", false), BotCase188("hello everyone", true),
    BotCase188("x".repeat(3999) + "\uD83D\uDD11" + "y".repeat(5), true),
    BotCase188("x".repeat(4000) + "tail", false),
    BotCase188("x".repeat(4001), false),
    BotCase188("  /start  ", false), BotCase188("\t/cmd\targ\t", false),
    BotCase188("/cmd@bot_name_123 extra words here", false),
    BotCase188("/cmd \uD83D\uDE00", false),
    BotCase188("bot_123", false), BotCase188("/start@bot extra", true),
)

internal fun botProdResult188(c: BotCase188): String {
    val cipher = BotCommandPolicy.looksLikeCiphertext(c.text)
    val san = BotCommandPolicy.sanitizeInboxText(c.text)
    val slash = BotCommandPolicy.parseSlash(c.text)
    val mention = BotCommandPolicy.parseMention(c.text)
    val accept = BotCommandPolicy.shouldAcceptInbox(c.text, c.direct)
    return buildString {
        append(if (cipher) "1" else "0")
        append("|")
        append(san?.let { "S" + hexOf188(it) } ?: "N")
        append("|")
        append(slash?.let { "S" + it.command + "," + (it.targetUsername ?: "") + "," + hexOf188(it.arguments) } ?: "N")
        append("|")
        append(mention?.let { "M" + it.username + "," + hexOf188(it.text) } ?: "N")
        append("|")
        append(if (accept) "1" else "0")
    }
}

class BotCommandParsingFuzzTest {

    @Test
    fun pinnedAndPools188() {
        for (c in BOT_PINNED188) {
            assertEquals(botResultWireJson188(c), botProdResult188(c), "pinned $c")
        }
        for (cmd in BOT_CMDS188) for (user in BOT_USERS188) for (tail in listOf("", " ", " x", "\targ")) {
            val c = BotCase188("/$cmd@$user$tail", false)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "pool slash $c")
        }
        for (user in BOT_USERS188) for (tail in listOf("", " ", " hi")) {
            val c = BotCase188("@$user$tail", true)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "pool mention $c")
        }
        for (cipher in BOT_CIPHER188) for (direct in listOf(false, true)) {
            val c = BotCase188(cipher, direct)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "pool cipher $c")
        }
    }

    @Test
    fun randomBotCases188() {
        val r = Random(42)
        repeat(1500) {
            val c = randomBotCase188(r)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "random $c")
        }
    }

    @Test
    fun botBoundarySweeps188() {
        for (n in 0..34) {
            val c = BotCase188("/" + "a".repeat(n), false)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "cmd len $n")
        }
        for (n in 0..34) {
            val c = BotCase188("/cmd@" + "a".repeat(n), false)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "user len $n")
        }
        for (n in 0..34) {
            val c = BotCase188("@" + "a".repeat(n), false)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "mention len $n")
        }
        for (ws in BOT_WS188) {
            val c = BotCase188("/cmd" + ws + "arg", false)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "ws slash $ws")
            val m = BotCase188("@bot_name" + ws + "hi", false)
            assertEquals(botResultWireJson188(m), botProdResult188(m), "ws mention $ws")
        }
        for (n in listOf(3998, 3999, 4000, 4001, 4002)) {
            val c = BotCase188("x".repeat(n), true)
            assertEquals(botResultWireJson188(c), botProdResult188(c), "take len $n")
            val astral = BotCase188("x".repeat(n - 1) + "\uD83D\uDD11", true)
            assertEquals(botResultWireJson188(astral), botProdResult188(astral), "take astral $n")
        }
    }
}
