package com.maodouchat.data.repository

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * G328c：`data/repository` 下那批 `…NetworkRepository` 的**转发契约**。
 *
 * 这 10 个类是 ui→network 迁移的落点，都很薄（一行转发）。薄到「看起来不可能出错」，
 * 但有一类错**编译器抓不到**：两个参数同类型时把顺序写反——
 * `{ token, postId -> ApiService.likePost(postId, token) }` 照样编译，
 * 到了运行期就是拿帖子 id 当令牌去请求（401 或者更糟：操作了别人的帖子）。
 * 端点映射的错误同样无声：`ApiService.getTotpStatus`（返回原始 JSON）与
 * `ApiService.totpStatus`（返回 Boolean）是两个端点，接错了不报错，只是行为不对。
 *
 * 所以这里断言的是**Kotlin 类型系统看不见的那部分**：每一个参数按位置原样透传、
 * 默认值真的是那个值、`Result`（成功值身份与失败实例）不被包装或吞掉。
 * 用 lambda 注入就是为这个——不需要 mockk，也不需要起网络。
 */
class NetworkRepositoryForwardingTest {

    @Test
    fun `PostNetworkRepository 按位置透传五个参数且默认值就是 40-null-null-null`() {
        var seen: List<Any?>? = null
        val repo = PostNetworkRepository(
            postsApi = { token, limit, before, beforeId, authorId ->
                seen = listOf(token, limit, before, beforeId, authorId)
                Result.success(emptyList())
            },
        )

        runBlocking {
            repo.posts("tok", before = 7L, beforeId = "p9", authorId = "u1")
            assertEquals(listOf<Any?>("tok", 40, 7L, "p9", "u1"), seen)

            repo.posts("tok2")
            // 默认 limit=40 必须原样落到端点：写错成 0 或 20 会静默少拉一半动态。
            assertEquals(listOf<Any?>("tok2", 40, null, null, null), seen)
        }
    }

    @Test
    fun `点赞与取消各走自己的端点——两者的参数类型相同，写反了编译器不报错`() {
        val calls = mutableListOf<String>()
        val repo = PostNetworkRepository(
            likeApi = { token, postId -> calls += "like:$token:$postId"; Result.failure(UnsupportedOperationException()) },
            unlikeApi = { token, postId -> calls += "unlike:$token:$postId"; Result.failure(UnsupportedOperationException()) },
        )

        runBlocking {
            repo.like("tok", "p1")
            repo.unlike("tok", "p2")
        }
        assertEquals(listOf("like:tok:p1", "unlike:tok:p2"), calls)
    }

    @Test
    fun `失败实例原样返回——仓库不吞错也不改写成别的异常`() {
        val boom = IllegalStateException("网络断了")
        val post = PostDto(id = "p1", author = UserDto(id = "u1", name = "作者"), content = "正文", createdAt = 0L)
        val repo = PostNetworkRepository(
            postsApi = { _, _, _, _, _ -> Result.failure(boom) },
            likeApi = { _, _ -> Result.success(post) },
        )

        runBlocking {
            val failed = repo.posts("tok")
            assertTrue(failed.isFailure)
            // 身份相同：网关/重试层靠异常类型分支，包一层就分不出来了。
            assertSame(boom, failed.exceptionOrNull())

            assertSame(post, repo.like("tok", "p1").getOrNull())
        }
    }

    @Test
    fun `ChatNetworkRepository 创建与删除都把 token 放第一位、chatId 第二位`() {
        var createArgs: List<Any?>? = null
        var deleteArgs: List<Any?>? = null
        val repo = ChatNetworkRepository(
            createChatApi = { token, peerIds, isGroup, groupName, chatType ->
                createArgs = listOf(token, peerIds, isGroup, groupName, chatType)
                Result.failure(UnsupportedOperationException())
            },
            deleteChatApi = { token, chatId ->
                deleteArgs = listOf(token, chatId)
                Result.failure(UnsupportedOperationException())
            },
        )

        runBlocking {
            repo.createChat("tok", listOf("u2"), groupName = "群")
            assertEquals(listOf<Any?>("tok", listOf("u2"), false, "群", null), createArgs)

            repo.deleteChat("tok", "c1")
            assertEquals(listOf<Any?>("tok", "c1"), deleteArgs)
        }

        val tok = "tok"
        val okRepo = ChatNetworkRepository(getChatsApi = { token -> if (token == tok) Result.success(emptyList()) else Result.failure(UnsupportedOperationException()) })
        runBlocking {
            assertTrue(okRepo.chats(tok).isSuccess)
            assertTrue(okRepo.chats("别的令牌").isFailure)
        }
    }

    @Test
    fun `ModerationNetworkRepository 拉黑的是 userId 不是令牌，且已拉黑名单走独立端点`() {
        var blockArgs: List<Any?>? = null
        var idsToken: String? = null
        val repo = ModerationNetworkRepository(
            blockUserApi = { token, userId ->
                blockArgs = listOf(token, userId)
                Result.failure(UnsupportedOperationException())
            },
            blockedIdsApi = { token ->
                idsToken = token
                Result.success(listOf("u7"))
            },
        )

        runBlocking {
            repo.blockUser("tok", "u7")
            assertEquals(listOf<Any?>("tok", "u7"), blockArgs)

            // userId 与 token 都是 String：写反了就变成「用令牌当用户 id 拉黑」。
            assertEquals(listOf("u7"), repo.blockedUserIds("tok").getOrNull())
            assertEquals("tok", idsToken)
        }
    }

    @Test
    fun `TotpNetworkRepository 状态与原始状态体是两个端点，不许互相顶替`() {
        val hit = mutableListOf<String>()
        val repo = TotpNetworkRepository(
            statusApi = { hit += "parsed"; Result.success(true) },
            statusRawApi = { hit += "raw"; Result.success("""{"enabled":true}""") },
        )

        runBlocking {
            assertEquals(true, repo.status("tok").getOrNull())
            assertEquals("""{"enabled":true}""", repo.statusRaw("tok").getOrNull())
        }
        // 曾经把 raw 那个接成 parsed 那个：返回值类型不同才没编译过，
        // 但状态页依赖 raw 里的 `enabled` 字段做迁移，接错会静默退化。
        assertEquals(listOf("parsed", "raw"), hit)
    }

    @Test
    fun `AccountSecurityNetworkRepository 改资料的两个字段都可缺省`() {
        var args: List<Any?>? = null
        val repo = AccountSecurityNetworkRepository(
            updateProfileApi = { token, name, status ->
                args = listOf(token, name, status)
                Result.failure(UnsupportedOperationException())
            },
        )

        runBlocking {
            repo.updateProfile("tok")
            // 两个都 null = 「只改没提到的字段」，服务端据此做部分更新；
            // 若默认值写成 "" 会把名字/签名清空。
            assertEquals(listOf<Any?>("tok", null, null), args)

            repo.updateProfile("tok", name = "新名")
            assertEquals(listOf<Any?>("tok", "新名", null), args)
        }
    }
}
