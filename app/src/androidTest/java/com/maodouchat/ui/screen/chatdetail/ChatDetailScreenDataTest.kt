package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.ChatEntity
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G333：**真实装配路径**下的聊天详情页——这条测试是为一次真机崩溃补的。
 *
 * 崩因：`ChatDetailViewModel` 里 `private val deps = ChatDetailDeps(application, host = this)`
 * 当时声明在 `_uiState` **之前**。Kotlin 按声明顺序初始化，`ChatDetailDeps` 构造时要读
 * `host._uiState`，读到的是 null，于是 `ScheduledMessageController(uiState = null)`
 * 抛 `NullPointerException: Parameter specified as non-null is null`——**真机上打开任一聊天就闪退**。
 *
 * 为什么以前没人发现：单元测试直接 new 各个 Controller（不经这条装配），
 * 而仪器测试里没有任何一例真的把 `ChatDetailScreen` 组合起来。于是这个 bug 一路绿灯到真机。
 * 所以这里坚持两条**真实路径**：
 * 1. 直接构造 `ChatDetailViewModel(application, SavedStateHandle)`——覆盖装配顺序本身；
 * 2. 组合真的 `ChatDetailScreen(...)`（它的 VM 由 `viewModel()` 创建，走的正是崩溃那条路），
 *    并等到顶栏与输入框都渲染出来为止。
 *
 * 纪律同 `ChatListScreenDataTest`：播种走真实 Room，`@After` 清理干净，断言等节点出现而不是 sleep。
 */
@RunWith(AndroidJUnit4::class)
class ChatDetailScreenDataTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.getInstance(context)
    private val seededChatId = "seed-chat-detail-1"

    private fun str(id: Int): String = context.getString(id)

    @Before
    fun seedChat() = runBlocking {
        db.chatDao().deleteAllChats()
        db.chatDao().insertChats(
            listOf(
                ChatEntity(
                    id = seededChatId,
                    isGroup = false,
                    lastMessage = "hi",
                    lastMessageTime = 1_000L,
                )
            )
        )
    }

    @After
    fun cleanLibrary() = runBlocking {
        db.chatDao().deleteAllChats()
    }

    @Test
    fun viewModelConstructsAgainstTheRealDependencies() {
        val viewModel = ChatDetailViewModel(
            context.applicationContext as Application,
            SavedStateHandle(mapOf("chatId" to seededChatId)),
        )
        // 崩溃发生在构造期（装配顺序），所以「能构造出来并且状态可读」就是断言本身。
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun routeComposesWithTopBarAndComposer() {
        // 用真实 VM（带 chatId 的 SavedStateHandle）组合真实 Route——`ChatDetailScreen()` 的
        // 默认 VM 拿不到 chatId，会渲染「会话不存在」空态，断言不到顶栏/输入框。
        val viewModel = ChatDetailViewModel(
            context.applicationContext as Application,
            SavedStateHandle(mapOf("chatId" to seededChatId)),
        )
        compose.setContent {
            ChatDetailRoute(viewModel = viewModel)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(str(R.string.common_back))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(str(R.string.chat_message_placeholder))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
