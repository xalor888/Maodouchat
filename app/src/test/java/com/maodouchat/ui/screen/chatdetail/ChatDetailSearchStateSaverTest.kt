package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `ChatDetailSearchState` 的 Saver 往返与 close() 语义。 */
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

    @Test
    fun `close keeps mode scope and window`() {
        val state = ChatDetailSearchState(
            showSearchBar = true,
            searchQuery = "abc",
            searchIndex = 2,
            searchMode = ChatSearchMode.SEMANTIC,
            searchScope = ChatSearchScope.MEDIA,
            searchWindow = ChatSearchWindow.SEVEN_DAYS,
        )

        state.close()

        assertEquals(ChatSearchMode.SEMANTIC, state.searchMode)
        assertEquals(ChatSearchScope.MEDIA, state.searchScope)
        assertEquals(ChatSearchWindow.SEVEN_DAYS, state.searchWindow)
    }

    @Test
    fun `defaults survive a round trip`() {
        val original = ChatDetailSearchState()
        val saved = with(ChatDetailSearchState.Saver) { scope.save(original) }
        val restored = assertNotNull(ChatDetailSearchState.Saver.restore(assertNotNull(saved)))

        assertFalse(restored.showSearchBar)
        assertEquals("", restored.searchQuery)
        assertEquals(0, restored.searchIndex)
        assertEquals(ChatSearchMode.KEYWORD, restored.searchMode)
        assertEquals(ChatSearchScope.ALL, restored.searchScope)
        assertEquals(ChatSearchWindow.ALL, restored.searchWindow)
    }

    @Test
    fun `enums are stored by name`() {
        val original = ChatDetailSearchState(
            searchMode = ChatSearchMode.SEMANTIC,
            searchScope = ChatSearchScope.MENTIONS,
            searchWindow = ChatSearchWindow.THIRTY_DAYS,
        )
        val saved = with(ChatDetailSearchState.Saver) { scope.save(original) }
        assertNotNull(saved)
        @Suppress("UNCHECKED_CAST")
        val list = saved as List<Any?>

        assertEquals(6, list.size)
        assertEquals("SEMANTIC", list[3])
        assertEquals("MENTIONS", list[4])
        assertEquals("THIRTY_DAYS", list[5])
    }

    @Test
    fun `save reflects later mutations`() {
        val state = ChatDetailSearchState(searchQuery = "first")
        state.searchQuery = "second"
        state.searchIndex = 5

        val saved = with(ChatDetailSearchState.Saver) { scope.save(state) }
        val restored = assertNotNull(ChatDetailSearchState.Saver.restore(assertNotNull(saved)))

        assertEquals("second", restored.searchQuery)
        assertEquals(5, restored.searchIndex)
    }

    @Test
    fun `close is idempotent`() {
        val state = ChatDetailSearchState(showSearchBar = true, searchQuery = "abc", searchIndex = 3)

        state.close()
        state.close()

        assertFalse(state.showSearchBar)
        assertEquals("", state.searchQuery)
        assertEquals(0, state.searchIndex)
        state.searchQuery = "again"
        assertEquals("again", state.searchQuery)
    }
}
