package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

// ---------- oracle ----------

// Group-play JSON parsing restated by hand. Ground truth, read from
// GroupPlayJsonPolicy.kt / GroupPlayJson.kt:
//   - arrayText: blank text -> "[]"; otherwise kotlinx lenient parse.
//     A top-level array passes through; a top-level object is unwrapped
//     through the first wrapper key (items/data/polls/pks/chains/ranking/
//     list) holding an array, else wrapped as a single item when it has
//     an id/userId/question key, else error("expected json array").
//     Anything else (scalar/null) fails element.jsonObject.
//   - parseObjectList(text): JSONArray(arrayText(text)), non-object
//     elements skipped via optJSONObject. parsePolls / parseObjectList
//     themselves are already fuzz-covered and are NOT re-tested here.
//   - optString: missing/null -> ""; numbers/bools -> toString
//     (Double 1.0 -> "1.0"); objects/arrays -> compact toString.
//     optInt: numbers -> intValue (truncates); strings go through
//     stringToValue first ("42.5" -> 42, "1e2" -> 100, "01"/" 42" -> 0);
//     bools/null/objects -> 0. optBoolean: bools as-is; strings only
//     "true" (case-insensitive) -> true, everything else false.
//   - parseChainDetail / parsePollId: malformed JSON -> null
//     (runCatching); parseChainList/parseCheckinRanking/parsePkList
//     let arrayText/JSONArray throws escape.
private val LENIENT_JSON186 = Json { ignoreUnknownKeys = true; isLenient = true }

private val GROUP_PLAY_WRAPPER_KEYS186 = listOf(
    "items", "data", "polls", "pks", "chains", "ranking", "list"
)

internal fun escJsonGroupPlay186(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

// kotlinx toString escapes only control chars (plus " and \); astral
// chars go out raw, unlike the wire escaper below which \u-escapes
// lone surrogates so org.json can round-trip them.
internal fun kotlinxEscString186(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun kotlinxJsonString186(el: JsonElement): String = when (el) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (el.isString) kotlinxEscString186(el.content) else el.content
    is JsonArray -> "[" + el.joinToString(",") { kotlinxJsonString186(it) } + "]"
    is JsonObject -> "{" + el.entries.joinToString(",") { (k, v) ->
        kotlinxEscString186(k) + ":" + kotlinxJsonString186(v)
    } + "}"
}

internal fun arrayTextOracle186(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonArray(emptyList()).toString()
    val element = LENIENT_JSON186.parseToJsonElement(trimmed)
    if (element is JsonArray) return kotlinxJsonString186(element)
    val obj = element.jsonObject
    for (key in GROUP_PLAY_WRAPPER_KEYS186) {
        val child = obj[key]
        if (child is JsonArray) return kotlinxJsonString186(child)
    }
    if (obj.containsKey("id") || obj.containsKey("userId") || obj.containsKey("question")) {
        return kotlinxJsonString186(JsonArray(listOf(obj)))
    }
    error("expected json array")
}

internal fun <T> parseObjectListOracle186(arr: JSONArray?, map: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        map(o)
    }
}

internal fun parseChainListOracle186(text: String): List<ChainListItem> =
    parseObjectListOracle186(JSONArray(arrayTextOracle186(text))) { o ->
        ChainListItem(
            id = o.optString("id"),
            title = o.optString("title"),
            topic = o.optString("topic"),
            active = o.optBoolean("active"),
            entryCount = o.optInt("entryCount"),
            maxEntries = o.optInt("maxEntries"),
            myJoined = o.optBoolean("myJoined")
        )
    }

internal fun parseChainEntriesOracle186(arr: JSONArray?): List<ChainEntryItem> =
    parseObjectListOracle186(arr) { o ->
        ChainEntryItem(
            id = o.optString("id"),
            userId = o.optString("userId"),
            sequence = o.optInt("sequence"),
            content = o.optString("content")
        )
    }

internal fun parseChainDetailOracle186(text: String): GroupChainDetailState? {
    val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
    return GroupChainDetailState(
        chainId = o.optString("id"),
        title = o.optString("title"),
        topic = o.optString("topic"),
        active = o.optBoolean("active"),
        entryCount = o.optInt("entryCount"),
        maxEntries = o.optInt("maxEntries"),
        myJoined = o.optBoolean("myJoined"),
        entries = parseChainEntriesOracle186(o.optJSONArray("entries"))
    )
}

internal fun parseCheckinRankingOracle186(text: String): List<CheckinRankItem> =
    parseObjectListOracle186(JSONArray(arrayTextOracle186(text))) { o ->
        CheckinRankItem(
            userId = o.optString("userId"),
            streak = o.optInt("streak"),
            totalCount = o.optInt("totalCount")
        )
    }

internal fun parsePkListOracle186(text: String): List<PkListItem> =
    parseObjectListOracle186(JSONArray(arrayTextOracle186(text))) { o ->
        PkListItem(
            id = o.optString("id"),
            leftTitle = o.optString("leftTitle"),
            rightTitle = o.optString("rightTitle"),
            active = o.optBoolean("active"),
            leftCount = o.optInt("leftCount"),
            rightCount = o.optInt("rightCount"),
            totalVoters = o.optInt("totalVoters"),
            myChoice = o.optString("myChoice").takeIf { it.isNotBlank() }
        )
    }

internal fun parsePollIdOracle186(text: String): String? =
    runCatching { JSONObject(text).optString("id") }
        .getOrNull()?.takeIf { it.isNotBlank() }

internal fun stringListOracle186(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}

internal fun intListOracle186(arr: JSONArray?): List<Int> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.optInt(it) }
}

internal fun chainListWireJson186(items: List<ChainListItem>): String =
    "[" + items.joinToString(",") { it ->
        "{\"id\":${escJsonGroupPlay186(it.id)}," +
            "\"title\":${escJsonGroupPlay186(it.title)}," +
            "\"topic\":${escJsonGroupPlay186(it.topic)}," +
            "\"active\":${it.active}," +
            "\"entryCount\":${it.entryCount}," +
            "\"maxEntries\":${it.maxEntries}," +
            "\"myJoined\":${it.myJoined}}"
    } + "]"

internal fun chainEntriesWireJson186(items: List<ChainEntryItem>): String =
    "[" + items.joinToString(",") { it ->
        "{\"id\":${escJsonGroupPlay186(it.id)}," +
            "\"userId\":${escJsonGroupPlay186(it.userId)}," +
            "\"sequence\":${it.sequence}," +
            "\"content\":${escJsonGroupPlay186(it.content)}}"
    } + "]"

internal fun chainDetailWireJson186(d: GroupChainDetailState?): String {
    if (d == null) return "null"
    return "{\"chainId\":${escJsonGroupPlay186(d.chainId)}," +
        "\"title\":${escJsonGroupPlay186(d.title)}," +
        "\"topic\":${escJsonGroupPlay186(d.topic)}," +
        "\"active\":${d.active}," +
        "\"entryCount\":${d.entryCount}," +
        "\"maxEntries\":${d.maxEntries}," +
        "\"myJoined\":${d.myJoined}," +
        "\"entries\":${chainEntriesWireJson186(d.entries)}," +
        "\"submitting\":${d.submitting}}"
}

internal fun checkinWireJson186(items: List<CheckinRankItem>): String =
    "[" + items.joinToString(",") { it ->
        "{\"userId\":${escJsonGroupPlay186(it.userId)}," +
            "\"streak\":${it.streak}," +
            "\"totalCount\":${it.totalCount}}"
    } + "]"

internal fun pkListWireJson186(items: List<PkListItem>): String =
    "[" + items.joinToString(",") { it ->
        "{\"id\":${escJsonGroupPlay186(it.id)}," +
            "\"leftTitle\":${escJsonGroupPlay186(it.leftTitle)}," +
            "\"rightTitle\":${escJsonGroupPlay186(it.rightTitle)}," +
            "\"active\":${it.active}," +
            "\"leftCount\":${it.leftCount}," +
            "\"rightCount\":${it.rightCount}," +
            "\"totalVoters\":${it.totalVoters}," +
            "\"myChoice\":${it.myChoice?.let { v -> escJsonGroupPlay186(v) } ?: "null"}}"
    } + "]"

internal fun stringListWireJson186(items: List<String>): String =
    "[" + items.joinToString(",") { escJsonGroupPlay186(it) } + "]"

internal fun intListWireJson186(items: List<Int>): String =
    "[" + items.joinToString(",") { it.toString() } + "]"

internal fun optStringWireJson186(v: String?): String =
    v?.let { escJsonGroupPlay186(it) } ?: "null"

// ---------- production ----------

internal fun arrayTextProduction186(text: String): String = GroupPlayJson.arrayText(text)

internal fun parseChainListProduction186(text: String): List<ChainListItem> =
    GroupPlayJsonPolicy.parseChainList(text)

internal fun parseChainEntriesProduction186(arr: JSONArray?): List<ChainEntryItem> =
    GroupPlayJsonPolicy.parseChainEntries(arr)

internal fun parseChainDetailProduction186(text: String): GroupChainDetailState? =
    GroupPlayJsonPolicy.parseChainDetail(text)

internal fun parseCheckinRankingProduction186(text: String): List<CheckinRankItem> =
    GroupPlayJsonPolicy.parseCheckinRanking(text)

internal fun parsePkListProduction186(text: String): List<PkListItem> =
    GroupPlayJsonPolicy.parsePkList(text)

internal fun parsePollIdProduction186(text: String): String? =
    GroupPlayJsonPolicy.parsePollId(text)

internal fun stringListProduction186(arr: JSONArray?): List<String> =
    GroupPlayJsonPolicy.stringList(arr)

internal fun intListProduction186(arr: JSONArray?): List<Int> =
    GroupPlayJsonPolicy.intList(arr)

// ---------- generators ----------

internal val GROUPPLAY_TEXTS186: List<String> = listOf(
    "",
    " ",
    "[]",
    "[{\"id\":\"c1\",\"title\":\"t\",\"topic\":\"tp\",\"active\":true,\"entryCount\":3,\"maxEntries\":10,\"myJoined\":false}]",
    "[{\"id\":\"c1\"},{\"id\":\"c2\",\"title\":\"x\"},2,\"s\",null,true,{\"id\":\"c3\"}]",
    "{\"id\":\"c1\",\"title\":\"t\"}",
    "{\"userId\":\"u1\",\"streak\":5,\"totalCount\":9}",
    "{\"question\":\"q?\",\"options\":[\"a\",\"b\"]}",
    "{\"items\":[{\"id\":\"c1\"}]}",
    "{\"data\":[{\"id\":\"c1\"},{\"id\":\"c2\"}]}",
    "{\"polls\":[{\"id\":\"p1\"}]}",
    "{\"pks\":[{\"id\":\"k1\"}]}",
    "{\"chains\":[{\"id\":\"c1\"}]}",
    "{\"ranking\":[{\"userId\":\"u1\",\"streak\":2}]}",
    "{\"list\":[{\"id\":\"c1\"}]}",
    "{\"items\":[]}",
    "{\"items\":{\"id\":\"c1\"}}",
    "{\"items\":[1,2]}",
    "{\"data\":\"nope\"}",
    "{\"other\":1}",
    "{\"error\":\"boom\"}",
    "{\"error\":\"boom\",\"message\":\"m\"}",
    "{\"id\":42}",
    "{\"id\":true}",
    "{\"id\":null}",
    "{\"id\":[1,2]}",
    "{\"id\":{\"a\":1}}",
    "{\"id\":\"c1\",\"entryCount\":\"42\",\"maxEntries\":\"42.5\",\"active\":\"true\"}",
    "{\"id\":\"c1\",\"entryCount\":\"01\",\"maxEntries\":\" 42\",\"active\":\" true\"}",
    "{\"id\":\"c1\",\"entryCount\":2147483647,\"maxEntries\":-2147483648}",
    "{\"id\":\"c1\",\"entryCount\":2147483648,\"maxEntries\":9223372036854775807}",
    "{\"id\":\"c1\",\"entryCount\":1.9,\"maxEntries\":-2.9,\"active\":1}",
    "{\"id\":\"c1\",\"entryCount\":1e2,\"maxEntries\":1.5}",
    "{\"id\":\"c1\",\"entryCount\":null,\"maxEntries\":null,\"active\":null}",
    "{\"id\":\"c1\",\"entryCount\":[1],\"maxEntries\":{\"x\":1},\"active\":[true]}",
    "{\"id\":\"c1\",\"myChoice\":\"\"}",
    "{\"id\":\"c1\",\"myChoice\":\"  \"}",
    "{\"id\":\"c1\",\"myChoice\":\"a\"}",
    "{\"id\":\"c1\",\"myChoice\":42}",
    "{\"id\":\"c1\",\"myChoice\":null}",
    "[{\"userId\":\"u1\",\"streak\":\"7\",\"totalCount\":3.7}]",
    "[{\"userId\":5,\"streak\":true,\"totalCount\":\"10\"}]",
    "[{\"leftTitle\":\"L\",\"rightTitle\":\"R\",\"leftCount\":\"3\",\"totalVoters\":1e3}]",
    "{\"entries\":[{\"id\":\"e1\",\"userId\":\"u1\",\"sequence\":1,\"content\":\"hi\"}]}",
    "{\"entries\":[{\"id\":\"e1\"},\"junk\",null,{\"id\":\"e2\",\"sequence\":\"2\"}]}",
    "{\"entries\":\"nope\"}",
    "{\"entries\":null}",
    "{\"entries\":{}}",
    "{\"id\":\"d1\",\"entries\":[{\"id\":\"e1\"}]}",
    "{\"id\":\"\",\"entries\":[]}",
    "{\"id\":\"  \"}",
    "{\"id\":42}",
    "{\"id\":1.5}",
    "{\"id\":null}",
    "{\"id\":[1]}",
    "{\"id\":{\"a\":1}}",
    "{\"id\":\"x\"} trailing",
    "{\"id\":\"x\"}",
    "not json",
    "{oops",
    "[1,2",
    "{\"a\":1,}",
    "{'id':'c1'}",
    "{id:'c1'}",
    "{\"id\":'c1'}",
    "{\"id\":hello}",
    "{\"id\":01}",
    "{\"id\":+5}",
    "{\"id\":.5}",
    "{\"id\":NaN}",
    "{\"id\":Infinity}",
    "{\"id\":-Infinity}",
    "{\"id\":0x10}",
    "{\"id\":1.}",
    "{\"id\":-.5}",
    "{\"id\":1e2}",
    "{\"id\":1.50}",
    "{\"id\":9223372036854775808}",
    "{\"a\":1} garbage",
    "123",
    "null",
    "true",
    "\"str\"",
    "[1,2,]",
    "[1 2]",
    "[{\"id\":\"c1\"},]",
    "{\"items\":[{\"id\":\"c1\"},]}",
    "{\"id\":\"caf\\u00e9\"}",
    "{\"id\":\"a\\nb\"}",
    "{\"id\":\"\\uD83D\\uDE00\"}",
    "[\"\\uD83D\\uDE00\"]",
    "{\"ID\":\"c1\",\"Id\":\"c2\"}",
    "{\"id\":\"c1\",\"id\":\"c2\"}",
)

internal val GROUPPLAY_ARR_TEXTS186: List<String> = listOf(
    "[]",
    "[{\"id\":\"e1\",\"userId\":\"u1\",\"sequence\":1,\"content\":\"hi\"}]",
    "[{\"id\":\"e1\"},\"junk\",null,{\"id\":\"e2\",\"sequence\":\"2\"}]",
    "[1,2,3]",
    "[\"a\",\"b\",null,true,1.5,{\"id\":\"x\"},[9]]",
    "[\"42\",\"42.5\",\"1e2\",\"01\",\" 42\",\"42 \",\"true\",\"TRUE\",\"\",\"yes\",\"0x10\",\"NaN\"]",
    "[0,1,-1,2147483647,-2147483648,2147483648,9223372036854775807,1.9,-2.9,1e2,null,true,\"42\"]",
    "[{\"id\":\"e1\"},]",
    "[1,2,]",
    "[,]",
    "[1 2]",
    "[\"a\" 'b']",
    "[01, +5, .5, NaN]",
    "[1e2, -0.0, 10000000000]",
    "not an array",
    "{}",
    "{\"id\":\"e1\"}",
    "[{\"id\":\"e1\"}",
    "",
    " ",
)

internal val GROUPPLAY_KEYS186: List<String> = listOf(
    "id", "userId", "question", "title", "topic", "active", "entryCount",
    "maxEntries", "myJoined", "streak", "totalCount", "leftTitle",
    "rightTitle", "leftCount", "rightCount", "totalVoters", "myChoice",
    "multi", "anonymous", "closed", "counts", "options", "myVotes",
    "sequence", "content", "entries", "items", "data", "polls", "error",
    "message", "other", "a", "b", "x"
)

internal val GROUPPLAY_SCALARS186: List<String> = listOf(
    "null", "true", "false", "0", "1", "-1", "42", "2147483647",
    "-2147483648", "2147483648", "9223372036854775807",
    "9223372036854775808", "1.0", "1.5", "-2.9", "1e2", "1E-7", "0.00001",
    "\"\"", "\"a\"", "\"hello world\"", "\"caf\\u00e9\"", "\"a\\nb\"",
    "\"true\"", "\"42\"", "\"42.5\"", "\"1e2\"", "\"01\"", "\" 42\"",
    "\"42 \"", "\"TRUE\"", "\"yes\"", "\"0x10\"", "\"NaN\"",
)

internal data class GroupPlayCase186(val text: String, val which: String)

internal fun randomJsonText186(rnd: Random, depth: Int): String {
    if (depth <= 0) return GROUPPLAY_SCALARS186.random(rnd)
    return when (rnd.nextInt(0, 4)) {
        0, 3 -> GROUPPLAY_SCALARS186.random(rnd)
        1 -> "[" + (0 until rnd.nextInt(0, 4)).joinToString(",") {
            randomJsonText186(rnd, depth - 1)
        } + "]"
        else -> "{" + (0 until rnd.nextInt(0, 4)).joinToString(",") {
            escJsonGroupPlay186(GROUPPLAY_KEYS186.random(rnd)) + ":" +
                randomJsonText186(rnd, depth - 1)
        } + "}"
    }
}

internal fun randomGroupPlayCase186(rnd: Random): GroupPlayCase186 {
    val which = listOf(
        "chainlist", "chainentries", "chaindetail", "checkin", "pklist",
        "pollid", "stringlist", "intlist", "arraytext"
    ).random(rnd)
    val text = when (rnd.nextInt(0, 5)) {
        0 -> GROUPPLAY_TEXTS186.random(rnd)
        1 -> GROUPPLAY_ARR_TEXTS186.random(rnd)
        2 -> randomJsonText186(rnd, 3)
        3 -> {
            val base = randomJsonText186(rnd, 2)
            if (base.startsWith("{") && base.endsWith("}")) {
                "{\"items\":$base}"
            } else {
                base
            }
        }
        else -> {
            val inner = (0 until rnd.nextInt(0, 4)).joinToString(",") {
                randomJsonText186(rnd, 1)
            }
            "[$inner]"
        }
    }
    return GroupPlayCase186(text, which)
}

internal fun groupPlayCaseWireJson186(c: GroupPlayCase186): String =
    "{\"text\":${escJsonGroupPlay186(c.text)},\"which\":${escJsonGroupPlay186(c.which)}}"

class GroupPlayJsonPolicyFuzzTest {

    private fun checkGroupPlay186(c: GroupPlayCase186) {
        val oracle = runCatching { groupPlayOracle186(c) }
        val production = runCatching { groupPlayProduction186(c) }
        val oEx = oracle.exceptionOrNull()?.javaClass?.simpleName
        val pEx = production.exceptionOrNull()?.javaClass?.simpleName
        assertEquals(oEx, pEx, "throw class for $c")
        if (oEx == null) {
            assertEquals(oracle.getOrNull(), production.getOrNull(), "value for $c")
        }
    }

    private fun groupPlayOracle186(c: GroupPlayCase186): String = when (c.which) {
        "chainlist" -> chainListWireJson186(parseChainListOracle186(c.text))
        "chainentries" -> chainEntriesWireJson186(
            parseChainEntriesOracle186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        "chaindetail" -> chainDetailWireJson186(parseChainDetailOracle186(c.text))
        "checkin" -> checkinWireJson186(parseCheckinRankingOracle186(c.text))
        "pklist" -> pkListWireJson186(parsePkListOracle186(c.text))
        "pollid" -> optStringWireJson186(parsePollIdOracle186(c.text))
        "stringlist" -> stringListWireJson186(
            stringListOracle186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        "intlist" -> intListWireJson186(
            intListOracle186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        else -> escJsonGroupPlay186(arrayTextOracle186(c.text))
    }

    private fun groupPlayProduction186(c: GroupPlayCase186): String = when (c.which) {
        "chainlist" -> chainListWireJson186(parseChainListProduction186(c.text))
        "chainentries" -> chainEntriesWireJson186(
            parseChainEntriesProduction186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        "chaindetail" -> chainDetailWireJson186(parseChainDetailProduction186(c.text))
        "checkin" -> checkinWireJson186(parseCheckinRankingProduction186(c.text))
        "pklist" -> pkListWireJson186(parsePkListProduction186(c.text))
        "pollid" -> optStringWireJson186(parsePollIdProduction186(c.text))
        "stringlist" -> stringListWireJson186(
            stringListProduction186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        "intlist" -> intListWireJson186(
            intListProduction186(runCatching { JSONArray(c.text) }.getOrNull())
        )
        else -> escJsonGroupPlay186(arrayTextProduction186(c.text))
    }

    @Test
    fun pinnedGroupPlayJson() {
        for (text in GROUPPLAY_TEXTS186) {
            for (which in listOf("chainlist", "chaindetail", "checkin", "pklist", "pollid", "arraytext")) {
                checkGroupPlay186(GroupPlayCase186(text, which))
            }
        }
        for (text in GROUPPLAY_ARR_TEXTS186) {
            for (which in listOf("chainentries", "stringlist", "intlist")) {
                checkGroupPlay186(GroupPlayCase186(text, which))
            }
        }
    }

    @Test
    fun randomGroupPlayJsonMatchProduction() {
        val rnd = Random(186001)
        repeat(2500) { checkGroupPlay186(randomGroupPlayCase186(rnd)) }
    }

    @Test
    fun sweepGroupPlayJsonBoundaries() {
        // Every wrapper key holding an array; every item key alone.
        for (key in GROUP_PLAY_WRAPPER_KEYS186) {
            checkGroupPlay186(GroupPlayCase186("{\"$key\":[{\"id\":\"c1\"}]}", "chainlist"))
            checkGroupPlay186(GroupPlayCase186("{\"$key\":\"nope\"}", "chainlist"))
        }
        for (key in listOf("id", "userId", "question")) {
            checkGroupPlay186(GroupPlayCase186("{\"$key\":\"v\"}", "chainlist"))
        }
        // optInt edges in the entryCount slot, as JSON values and as strings.
        for (v in listOf("0", "1", "-1", "42", "2147483647", "-2147483648", "2147483648",
            "9223372036854775807", "9223372036854775808", "1.0", "1.9", "-2.9",
            "1e2", "1E-7", "0.00001", "true", "false", "null", "\"42\"",
            "\"42.5\"", "\"1e2\"", "\"01\"", "\" 42\"", "\"42 \"", "\"TRUE\"",
            "\"\"", "[1]", "{\"a\":1}")) {
            checkGroupPlay186(GroupPlayCase186("{\"id\":\"c1\",\"entryCount\":$v}", "chainlist"))
            checkGroupPlay186(GroupPlayCase186("[$v]", "intlist"))
        }
        // optBoolean edges in the active slot.
        for (v in listOf("true", "false", "\"true\"", "\"TRUE\"", "\"True\"", "\" true\"",
            "\"false\"", "\"yes\"", "\"1\"", "1", "0", "null", "[]", "{}")) {
            checkGroupPlay186(GroupPlayCase186("{\"id\":\"c1\",\"active\":$v}", "chainlist"))
        }
        // optString edges in the id slot.
        for (v in listOf("\"x\"", "42", "1.0", "1e2", "true", "null", "[1,2]", "{\"a\":1,\"b\":2}",
            "{\"b\":2,\"a\":1}", "[]", "{}")) {
            checkGroupPlay186(GroupPlayCase186("{\"id\":$v}", "chainlist"))
            checkGroupPlay186(GroupPlayCase186("{\"id\":$v}", "pollid"))
        }
    }
}
