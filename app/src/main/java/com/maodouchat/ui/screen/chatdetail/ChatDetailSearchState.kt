package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * G335：**会话内搜索**的六个状态（搜索栏开没开、关键词、命中下标、模式、范围、时间窗）。
 *
 * 归成一族：它们共同描述「这一次搜索」，任何一个单独变化都要和其余一起读才有意义
 * （比如 `searchIndex` 只在 `searchQuery` 的结果集里有意义）。原先散在 Route 里 6 行声明
 * 加十几处读写，读的人要在几十个状态里辨认「哪些属于搜索」。
 *
 * ⚠️ **这一族是 `rememberSaveable`，所以持有类必须自带 [Saver]**：
 * 普通持有类拿不到保存语义，硬搬会**静默**失去「旋转屏/进程重建后搜索词还在」的行为。
 * 这也是本类存在的第二个意义——它给出了这个文件里 43 个 saveable 状态的搬迁范式：
 * 1. 构造器给默认值（重建时用来还原）；
 * 2. 字段用 `mutableStateOf`；
 * 3. 配套一个 `listSaver`（元素都必须是 Bundle 可存的基本类型，枚举按 `name` 存）；
 * 4. 用 [rememberChatDetailSearchState] 创建——调用点看起来和原来的 `rememberSaveable` 一样，
 *    所以**保存语义不变**这件事在调用点就能看出来。
 */
internal class ChatDetailSearchState(
    showSearchBar: Boolean = false,
    searchQuery: String = "",
    searchIndex: Int = 0,
    searchMode: ChatSearchMode = ChatSearchMode.KEYWORD,
    searchScope: ChatSearchScope = ChatSearchScope.ALL,
    searchWindow: ChatSearchWindow = ChatSearchWindow.ALL,
) {
    var showSearchBar by mutableStateOf(showSearchBar)
    var searchQuery by mutableStateOf(searchQuery)
    var searchIndex by mutableIntStateOf(searchIndex)
    var searchMode by mutableStateOf(searchMode)
    var searchScope by mutableStateOf(searchScope)
    var searchWindow by mutableStateOf(searchWindow)

    /** 关掉搜索栏并清空这一次的搜索（返回聊天时调用）。 */
    fun close() {
        showSearchBar = false
        searchQuery = ""
        searchIndex = 0
    }

    companion object {
        /** 存/取都走基本类型 + 枚举名：Bundle 可存，且字段增删时编译器会提醒改这里。 */
        val Saver = listSaver<ChatDetailSearchState, Any>(
            save = {
                listOf(
                    it.showSearchBar,
                    it.searchQuery,
                    it.searchIndex,
                    it.searchMode.name,
                    it.searchScope.name,
                    it.searchWindow.name,
                )
            },
            restore = {
                ChatDetailSearchState(
                    showSearchBar = it[0] as Boolean,
                    searchQuery = it[1] as String,
                    searchIndex = it[2] as Int,
                    searchMode = ChatSearchMode.valueOf(it[3] as String),
                    searchScope = ChatSearchScope.valueOf(it[4] as String),
                    searchWindow = ChatSearchWindow.valueOf(it[5] as String),
                )
            },
        )
    }
}

/** 与原来的 `rememberSaveable` 等价（含旋转/进程重建恢复）。 */
@Composable
internal fun rememberChatDetailSearchState(): ChatDetailSearchState =
    rememberSaveable(saver = ChatDetailSearchState.Saver) { ChatDetailSearchState() }
