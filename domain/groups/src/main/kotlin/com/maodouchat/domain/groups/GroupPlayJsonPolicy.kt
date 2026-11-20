package com.maodouchat.domain.groups

import org.json.JSONArray
import org.json.JSONObject

// 群玩法列表解析：四处 ViewModel 各有一份逐字相同的 JSONArray 遍历骨架，
// 差别只在每行 JSONObject 到条目模型的映射。骨架收进 domain，映射留在调用方。
object GroupPlayJsonPolicy {

    fun <T> parseObjectList(text: String, map: (JSONObject) -> T): List<T> =
        parseObjectList(JSONArray(GroupPlayJson.arrayText(text)), map)

    fun <T> parseObjectList(arr: JSONArray?, map: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            map(o)
        }
    }

    // 投票详情里 options/counts/myVotes 三处同构的内嵌数组提取。
    fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    fun intList(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    // 四屏列表的行映射：字段提取逐字平移，骨架与缺省值语义不变。
    fun parseChainList(text: String): List<ChainListItem> =
        parseObjectList(text) { o ->
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

    fun parseChainEntries(arr: JSONArray?): List<ChainEntryItem> =
        parseObjectList(arr) { o ->
            ChainEntryItem(
                id = o.optString("id"),
                userId = o.optString("userId"),
                sequence = o.optInt("sequence"),
                content = o.optString("content")
            )
        }

    // 接龙详情是唯一残留的单对象解析：非法 JSON 返回 null，调用方沿用既有 not_found 分支。
    fun parseChainDetail(text: String): GroupChainDetailState? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return GroupChainDetailState(
            chainId = o.optString("id"),
            title = o.optString("title"),
            topic = o.optString("topic"),
            active = o.optBoolean("active"),
            entryCount = o.optInt("entryCount"),
            maxEntries = o.optInt("maxEntries"),
            myJoined = o.optBoolean("myJoined"),
            entries = parseChainEntries(o.optJSONArray("entries"))
        )
    }

    fun parsePollId(text: String): String? =
        runCatching { JSONObject(text).optString("id") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    fun parseCheckinRanking(text: String): List<CheckinRankItem> =
        parseObjectList(text) { o ->
            CheckinRankItem(
                userId = o.optString("userId"),
                streak = o.optInt("streak"),
                totalCount = o.optInt("totalCount")
            )
        }

    fun parsePkList(text: String): List<PkListItem> =
        parseObjectList(text) { o ->
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

    fun parsePolls(text: String): List<PollListItem> =
        parseObjectList(text) { o ->
            PollListItem(
                id = o.optString("id"),
                question = o.optString("question"),
                options = stringList(o.optJSONArray("options")),
                multi = o.optBoolean("multi"),
                anonymous = o.optBoolean("anonymous"),
                closed = o.optBoolean("closed"),
                counts = intList(o.optJSONArray("counts")),
                totalVoters = o.optInt("totalVoters"),
                myVotes = intList(o.optJSONArray("myVotes"))
            )
        }
}
