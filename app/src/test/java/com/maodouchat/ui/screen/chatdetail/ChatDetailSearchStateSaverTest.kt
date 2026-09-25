package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G335：`ChatDetailSearchState` 的 **Saver 往返**——搜索状态族从 `rememberSaveable`
 * 搬进持有类时，我在 KDoc 里承诺「保存语义不变」。这条测试就是那句话的证据：
 * 只把字段塞进类里、忘了写 Saver，旋转屏/进程重建后搜索词就会**静默**丢失，
 * 而这种丢失在真机上要手动转屏才看得见，在 CI 里永远看不见。
 *
 * 三件事：六个字段一个不落地往返；还原出来的是**独立实例**（改新的不影响旧的）；
 * `close()`（返回聊天时调用）把这一次搜索清干净。
 */
internal class ChatDetailSearchStateSaverTest {

    private val scope = SaverScope { true }

    @Test
    fun `saver round-trips every field`() {
        val original = ChatDetailSearchState(
            showSearchBar = true,
            searchQuery = "预算表",
            searchIndex = 7,
            searchMode = ChatSearchMode.SEMANTIC,
            searchScope = ChatSearchScope.MEDIA,
            searchWindow = ChatSearchWindow.SEVEN_DAYS,
        )

        val saved = with(ChatDetailSearchState.Saver) { scope.save(original) }
        assertNotNull(saved, "Saver 没产出任何东西——那说明有字段没进 save 列表")

        val restored = assertNotNull(ChatDetailSearchState.Saver.restore(saved), "存下去的东西还原不回来")

        assertTrue(restored.showSearchBar, "搜索栏开着的状态没保住")
        assertEquals("预算表", restored.searchQuery, "搜索词没保住——转屏后用户会看到空搜索框")
        assertEquals(7, restored.searchIndex, "命中下标没保住")
        assertEquals(ChatSearchMode.SEMANTIC, restored.searchMode, "搜索模式没保住")
        assertEquals(ChatSearchScope.MEDIA, restored.searchScope, "搜索范围没保住")
        assertEquals(ChatSearchWindow.SEVEN_DAYS, restored.searchWindow, "时间窗没保住")
    }

    @Test
    fun `restored holder is an independent instance`() {
        val original = ChatDetailSearchState(showSearchBar = true, searchQuery = "abc")
        val saved = with(ChatDetailSearchState.Saver) { scope.save(original) }
        assertNotNull(saved)
        val restored = ChatDetailSearchState.Saver.restore(saved)
        assertNotNull(restored)

        restored.searchQuery = "changed"

        assertEquals("abc", original.searchQuery, "还原出来的实例与原来共享状态——那是同一个对象，不是还原")
    }

    @Test
    fun `close clears this search but keeps the holder usable`() {
        val state = ChatDetailSearchState(showSearchBar = true, searchQuery = "abc", searchIndex = 3)

        state.close()

        assertFalse(state.showSearchBar, "关掉搜索栏后标志没复位")
        assertEquals("", state.searchQuery, "关掉搜索栏后搜索词还在，下次打开会看到上次的词")
        assertEquals(0, state.searchIndex, "命中下标没复位")

        // 仍然可以继续用（不是把对象作废）
        state.showSearchBar = true
        state.searchQuery = "x"
        assertTrue(state.showSearchBar)
        assertEquals("x", state.searchQuery)
    }
}
