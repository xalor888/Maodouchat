package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupPlayJson
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
}
