package com.maodouchat.ui.screen.explore

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G309c：`ExploreScreen` 本体的 **UI 层**覆盖（Q03 第 1 项的第四个入口）。
 *
 * **这一批的存在本身就是为了证伪我在 G307c 里写下的一句错话。**
 * 我当时记：「ExploreScreen 不可测——orchestrator 默认值依赖
 * `MaodouchatApp.instance.applicationScope` 全局单例，两个类都是具体类，
 * 无现成 fake 可复刻」。本轮核实后发现该结论把成本夸大了：
 * - `ExploreScreen(..., viewModel: ExploreViewModel = viewModel())` **接受 VM 参数**；
 * - `ExploreViewModel(application, feedController, orchestrator)` 三个依赖**都能显式传入**，
 *   且 `ExploreViewModel.kt:18` 的 `feedController = feedController` 说明 orchestrator 的
 *   默认值**引用 feedController 参数**——显式传 feedController 即传导进去；
 * - `FeedRepository` 只是 **5 个方法**的接口，fake 成本极低；
 * - `ExploreOrchestrator` 自己持有 `_uiState = MutableStateFlow(ExploreUiState(...))`，
 *   初始状态确定；而 `MaodouchatApp.instance` 只在**省略 orchestrator** 时才会被求值，
 *   所以连 orchestrator 也显式传（配一个测试 scope）即可完全绕开它。
 *
 * 真实成本 = fake 5 方法接口 + 显式传 2 个参数。**不是「需要改造」。**
 *
 * 覆盖的 state → UI 映射（此前只有编译保证）：
 * - 已登录 + 动态为空 → 空态三件套；
 * - 已登录 + 有动态 → 渲染动态正文；
 * - 点动态 → 触发屏幕自己的 `onOpenPost`（**屏幕级行为**，行级测试碰不到）。
 */
@RunWith(AndroidJUnit4::class)
class ExploreScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun post(
        id: String = "p1",
        content: String = "第一条动态",
    ) = PostDto(
        id = id,
        author = UserDto(id = "u1", name = "alice"),
        content = content,
        createdAt = 1_000L,
    )

    /**
     * @param posts 为 null 表示 load 返回空列表；否则返回给定动态。
     * @param loggedIn 为 false 时 currentSession() 返回 null（未登录）。
     */
    private fun createFakeFeedRepository(
        loggedIn: Boolean = true,
        posts: List<PostDto>? = null,
    ): FeedRepository = object : FeedRepository {
        override fun currentSession(): FeedSession? =
            if (loggedIn) FeedSession("owner-1") else null
        override fun isCurrent(session: FeedSession): Boolean = true
        override suspend fun load(
            session: FeedSession,
            cursor: ExploreFeedPolicy.Cursor?,
        ): Result<List<PostDto>> = Result.success(posts ?: emptyList())
        override suspend fun publish(
            session: FeedSession,
            content: String,
            imageUrls: List<String>,
            visibility: String?,
        ): Result<PostDto> = Result.failure(NotImplementedError("本测试不需要发布"))
    }

    private fun buildScreenViewModel(
        loggedIn: Boolean = true,
        posts: List<PostDto>? = null,
    ): ExploreViewModel {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as android.app.Application
        val feedController = FeedController(createFakeFeedRepository(loggedIn, posts))
        // orchestrator 也显式传：它的默认值会用 MaodouchatApp.instance.applicationScope
        // （全局单例，测试进程里未必初始化）。显式传一个测试 scope 即完全绕开。
        val orchestrator = ExploreOrchestrator(
            application = application,
            scope = CoroutineScope(Dispatchers.Main),
            feedController = feedController,
        )
        return ExploreViewModel(
            application = application,
            feedController = feedController,
            orchestrator = orchestrator,
        )
    }

    // ---------- 可行性探针 ----------

    @Test
    fun exploreScreenRendersItsTopBarTitleWithAnInjectedViewModel() {
        // 证明「显式传 VM」这条路走得通：默认的 viewModel() 不会被求值。
        compose.setContent { ExploreScreen(viewModel = buildScreenViewModel()) }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.nav_explore)).assertIsDisplayed()
    }

    // ---------- state → UI 映射 ----------

    @Test
    fun loggedInWithEmptyFeedRendersTheEmptyState() {
        // 已登录 + load 空 → 必须渲染空态三件套
        compose.setContent { ExploreScreen(viewModel = buildScreenViewModel(posts = null)) }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.explore_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.explore_empty_subtitle)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.explore_empty_action)).assertIsDisplayed()
    }

    @Test
    fun loggedInWithPostsRendersThePostContentInsteadOfEmptyState() {
        // 反向：有动态时**不得**出现空态标题，且必须渲染动态正文
        compose.setContent {
            ExploreScreen(viewModel = buildScreenViewModel(posts = listOf(post(content = "第一条动态"))))
        }
        compose.waitForIdle()

        compose.onNodeWithText("第一条动态").assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.explore_empty_title)).assertCountEquals(0)
    }

    // ---------- 屏幕级行为 ----------

    @Test
    fun clickingAPostFiresTheScreensOnOpenPostCallback() {
        // 屏幕自己接的 `onOpenPost`：点动态卡片必须回传**那条**动态的 id。
        // 这是屏幕级接线——行级 composable 测试碰不到。
        var opened: String? = null
        compose.setContent {
            ExploreScreen(
                viewModel = buildScreenViewModel(posts = listOf(post(id = "p7", content = "点我打开"))),
                onOpenPost = { opened = it },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("点我打开").assertIsDisplayed()
        compose.onNodeWithText("点我打开").performClick()
        assert(opened == "p7") { "点击动态后 onOpenPost 应回传 p7，实际 $opened" }
    }
}
