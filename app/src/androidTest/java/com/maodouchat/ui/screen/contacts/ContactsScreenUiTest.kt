package com.maodouchat.ui.screen.contacts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.conversation.ConversationCreationPort
import com.maodouchat.contacts.sync.ContactSyncEvent
import com.maodouchat.contacts.sync.ContactsRealtimeSyncCoordinator
import com.maodouchat.contacts.usecase.ContactMutationUseCase
import com.maodouchat.contacts.usecase.FriendRequestsSnapshot
import com.maodouchat.contacts.usecase.FriendRequestItem
import com.maodouchat.contacts.usecase.FriendRequestUseCase
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.network.GroupInviteAcceptResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G305c：**可行性实验**——ContactsScreen 本体能否在 instrumented Compose 测试里渲染。
 *
 * 这个文件要回答一个问题：我在 G301c 记下的卡点
 * 「ContactsScreen 带 viewModel() 默认参数，需先做依赖注入改造才能测」
 * **是否成立**。本轮实测的结论是**不成立**：
 * - 项目没有任何 DI 框架，但 `ContactsViewModel(application, ...)` 的其余依赖
 *   全部有默认值，且 **composable 的 `viewModel` 是普通参数**——
 *   调用方显式传入 fake VM 时，默认值根本不会求值；
 * - `application` 在 VM 内的全部用法（含 `application as MaodouchatApp` 硬转型）
 *   都位于**构造器的默认值表达式里**；把 5 个依赖全部注入 fake 后，
 *   `application` 一次都不会被用到，所以传真实 context 即可；
 * - `kotlinx-coroutines-test` 只有 testImplementation（JVM）没有 androidTest，
 *   但 **instrumented 测试跑在真机上，`Dispatchers.Main` 是真实的**，
 *   VM 的 `viewModelScope` 能自然工作，不需要 `Dispatchers.setMain`。
 *
 * 代价（已在台账记录取舍）：VM 的 5 个 fake 工厂是在 JVM 测试
 * `ContactsViewModelTest` 内部定义的私有函数，androidTest 看不到，
 * 因此这里**复刻**了一份（约 80 行）。这是重复代码，
 * 但比「为了让测试跑起来而改生产代码」或「新建共享 source set」都小。
 *
 * 本文件当前只有**一个**用例，用来先验证可行性；若它能绿，再按 G301c 的纪律补全。
 */
@RunWith(AndroidJUnit4::class)
class ContactsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /** 带格式参数的字符串（如「%1$d 人在线」）。 */
    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    // ---------- 以下 fake 复刻自 app/src/test/.../ContactsViewModelTest.kt（G305c） ----------

    private fun testUser(id: String, name: String) = User(
        id = id, name = name, email = "$id@example.com", isOnline = true
    )

    private fun createFakeContactsRepository(
        session: ContactsSession = ContactsSession("owner-1"),
        friends: List<User> = listOf(testUser("friend-1", "Alice"))
    ) = object : ContactsRepository {
        override fun currentSession(): ContactsSession? = session
        override fun isCurrent(session: ContactsSession): Boolean = true
        override fun observeFriends(session: ContactsSession): Flow<List<User>> = flowOf(friends)
        override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult =
            ContactsLoadResult(friends)
        override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult =
            ContactsLoadResult(friends.filter { it.name.contains(query, ignoreCase = true) })
    }

    private fun createFakeFriendRequestUseCase(
        onSend: (targetId: String, msg: String) -> Result<FriendRequestItem> = { id, msg ->
            Result.success(FriendRequestItem("req-1", testUser(id, "User $id"), msg, 1000L))
        },
        onAccept: (requestId: String) -> Result<FriendRequestItem?> = { id ->
            Result.success(FriendRequestItem(id, testUser("user-$id", "Accepted $id"), "", 1000L))
        },
        onReject: (requestId: String) -> Result<Unit> = { Result.success(Unit) },
        onCancel: (requestId: String) -> Result<Unit> = { Result.success(Unit) },
        onBatchAccept: (requestIds: List<String>) -> Map<String, Result<FriendRequestItem?>> = { ids ->
            ids.associateWith { Result.success(FriendRequestItem(it, testUser(it, "Accepted $it"), "", 1000L)) }
        },
        onBatchReject: (requestIds: List<String>) -> Map<String, Result<Unit>> = { ids ->
            ids.associateWith { Result.success(Unit) }
        },
        onLoad: () -> Result<FriendRequestsSnapshot> = {
            Result.success(FriendRequestsSnapshot(emptyList(), emptyList()))
        }
    ) = object : FriendRequestUseCase {
        override suspend fun loadRequests(): Result<FriendRequestsSnapshot> = onLoad()
        override suspend fun sendFriendRequest(targetUserId: String, note: String) = onSend(targetUserId, note)
        override suspend fun acceptFriendRequest(requestId: String) = onAccept(requestId)
        override suspend fun rejectFriendRequest(requestId: String) = onReject(requestId)
        override suspend fun cancelFriendRequest(requestId: String) = onCancel(requestId)
        override suspend fun batchAcceptFriendRequests(requestIds: List<String>) = onBatchAccept(requestIds)
        override suspend fun batchRejectFriendRequests(requestIds: List<String>) = onBatchReject(requestIds)
    }

    private fun createFakeContactMutationUseCase() = object : ContactMutationUseCase {
        override suspend fun setNickname(userId: String, nickname: String) = Result.success(Unit)
        override suspend fun removeFriend(userId: String) = Result.success(Unit)
        override suspend fun blockUser(userId: String) = Result.success(Unit)
        override suspend fun unblockUser(userId: String) = Result.success(Unit)
    }

    private fun createFakeConversationCreationPort() = object : ConversationCreationPort {
        override suspend fun createDirectChat(peerUserId: String) =
            Result.success(Chat(id = "chat-$peerUserId", isGroup = false, chatType = "DIRECT"))
        override suspend fun createSecretChat(peerUserId: String) =
            Result.success(Chat(id = "secret-$peerUserId", isGroup = false, chatType = "SECRET"))
        override suspend fun createGroupChat(name: String, memberUserIds: List<String>) =
            Result.success(Chat(id = "grp-1", isGroup = true, chatType = "GROUP", groupName = name))
        override suspend fun createChannelChat(name: String, memberUserIds: List<String>) =
            Result.success(Chat(id = "chan-1", isGroup = true, chatType = "CHANNEL", groupName = name))
    }

    private fun createFakeRealtimeCoordinator() = object : ContactsRealtimeSyncCoordinator {
        override val syncEvents: Flow<ContactSyncEvent> = MutableSharedFlow()
        override suspend fun handleEvent(
            event: RealtimeDomainEvent,
            expectedOwnerUserId: String,
            currentToken: String
        ) = Unit

        override fun startObserving(
            scope: kotlinx.coroutines.CoroutineScope,
            eventsFlow: Flow<RealtimeDomainEvent>,
            sessionProvider: () -> Pair<String?, String?>
        ): Job = Job()
    }

    private fun buildScreenViewModel(emptyFriends: Boolean = false): ContactsViewModel {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return ContactsViewModel(
            application = app.applicationContext as android.app.Application,
            contactsController = ContactsController(
                createFakeContactsRepository(
                    friends = if (emptyFriends) emptyList() else listOf(testUser("friend-1", "Alice"))
                )
            ),
            friendRequestUseCase = createFakeFriendRequestUseCase(),
            contactMutationUseCase = createFakeContactMutationUseCase(),
            conversationCreationPort = createFakeConversationCreationPort(),
            realtimeSyncCoordinator = createFakeRealtimeCoordinator(),
            groupInviteLoader = { Result.success(emptyList()) },
            groupInviteAcceptor = { _, _ -> Result.success(GroupInviteAcceptResponse("ok")) },
            groupInviteDecliner = { _, _ -> Result.success(GroupInviteAcceptResponse("ok")) }
        )
    }

    // ---------- 可行性实验本体 ----------

    @Test
    fun contactsScreenRendersItsSearchPlaceholderWithAnInjectedViewModel() {
        // 唯一目的：证明「显式传入 fake VM」这条路走得通——
        // 默认的 viewModel() 不会被求值，屏幕能基于 fake uiState 渲染。
        val vm = buildScreenViewModel()

        compose.setContent {
            ContactsScreen(viewModel = vm)
        }
        compose.waitForIdle()

        // 搜索框占位符是屏幕上稳定存在、与数据无关的文案
        compose.onNodeWithText(str(R.string.contacts_search_placeholder)).assertIsDisplayed()
    }

    // ---------- state → UI 映射（屏幕级测试的真正价值） ----------

    @Test
    fun emptyContactsListRendersTheEmptyStateWithBothActions() {
        // fake 仓库返回空列表 → 屏幕必须渲染空态三件套
        val vm = buildScreenViewModel(emptyFriends = true)

        compose.setContent { ContactsScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.contacts_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.contacts_empty_subtitle)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.contacts_empty_action_search)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.contacts_empty_action_scan)).assertIsDisplayed()
    }

    @Test
    fun nonEmptyContactsListRendersTheFriendNameInsteadOfEmptyState() {
        // 反向：有数据时**不得**出现空态标题，且必须渲染好友名。
        // 与上一条合起来钉住「空态与否由 state.contacts 决定」。
        val vm = buildScreenViewModel()

        compose.setContent { ContactsScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.contacts_empty_title)).assertCountEquals(0)
    }

    @Test
    fun onlineFriendsRendersTheOnlineCountLabel() {
        // state.onlineCount > 0 时才渲染「N 人在线」标签（state → UI）
        val vm = buildScreenViewModel()

        compose.setContent { ContactsScreen(viewModel = vm) }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.contacts_online_count, 1)).assertIsDisplayed()
    }

    // ---------- 屏幕级行为（这是行级测试做不到的） ----------

    @Test
    fun emptyStateScanActionFiresTheScreensOnOpenScanCallback() {
        // 屏幕自己接的 `onOpenScan`：空态的「扫一扫」按钮必须触发它。
        // 这是**屏幕级**行为——行级 composable 测试碰不到这层接线。
        var openScan = 0
        val vm = buildScreenViewModel(emptyFriends = true)

        compose.setContent {
            ContactsScreen(viewModel = vm, onOpenScan = { openScan++ })
        }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.contacts_empty_action_scan)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.contacts_empty_action_scan)).performClick()
        assert(openScan == 1) { "点「扫一扫」后 onOpenScan 回调应为 1，实际 $openScan" }
    }
}
