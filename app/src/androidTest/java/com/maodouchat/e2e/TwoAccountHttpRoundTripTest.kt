package com.maodouchat.e2e

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maodouchat.crypto.SignalKeyExchange
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.api.AuthApiClient
import com.maodouchat.network.api.ConversationApiClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 真客户端 ↔ 真服务端的端到端证据（G23 / M5 第一片）。
 *
 * 与 G22 的区别：G22 是**一个进程内**的装配（两个账号共用同一个内存库）。
 * 本文件跑在**模拟器**里，通过**真实 HTTP** 打一台**真的在宿主上运行的服务端进程**
 * （由 `scripts/two-device-http-e2e.sh` 起停），并且客户端用的是本仓库自己的网络层
 * （`AuthApiClient` 等，基础地址由 `-PMAODOU_API_BASE_URL=http://10.0.2.2:<port>` 注入）。
 *
 * 分层说明（避免把「脚本能起服务」说成「端到端通了」）：
 * - 第 1 步只证明**模拟器能经真实 HTTP 摸到宿主上的服务端**；
 * - 第 2 步证明**两个账号能用本仓库的登录客户端真登录**；
 * - 后续步骤再逐步把加密载荷真正送过网络（见文件末尾的进度记录）。
 */
@RunWith(AndroidJUnit4::class)
class TwoAccountHttpRoundTripTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val url = ApiConfig.BASE_URL
        assertTrue(
            "这个用例必须跑在注入的 E2E 服务端地址上，实际 BASE_URL=$url",
            url.contains("10.0.2.2") || url.contains("127.0.0.1") || url.contains("localhost"),
        )
        return url
    }

    @Test
    fun theEmulatorCanReachTheHostedServerOverRealHttp() {
        val url = baseUrl()
        val response = http.newCall(
            Request.Builder().url("$url/health/ready").get().build(),
        ).execute()
        response.use {
            assertEquals("服务端健康检查必须 200，实际 ${it.code}", 200, it.code)
            val body = it.body?.string().orEmpty()
            assertTrue("健康检查应当报告 ready：$body", body.contains("ready"))
        }
    }

    @Test
    fun twoDemoAccountsLogInThroughTheRealClientOverRealHttp() = runBlocking {
        baseUrl()
        val alex = AuthApiClient.login("alex@example.com", "password123", "").getOrNull()
        val alice = AuthApiClient.login("alice@example.com", "password123", "").getOrNull()

        assertTrue("alex 必须登录成功（服务端种子用户）", alex != null && alex.token.isNotBlank())
        assertTrue("alice 必须登录成功（服务端种子用户）", alice != null && alice.token.isNotBlank())
        assertTrue("两个账号必须是不同用户", alex!!.userId != alice!!.userId)
    }

    /**
     * 第 3 步：两个账号用**本仓库的生产 `SignalProtocol`** 做完整 bootstrap（生成身份密钥/预密钥）
     * 并通过**真实 HTTP** 上传到服务端，然后用本仓库的 key-exchange 客户端把对端的 bundle 拉回来。
     * 这一步证明的是「密钥交换真的过网络」，还没到「加密消息过网络」。
     */
    @Test
    fun bothAccountsBootstrapAndExchangeKeysOverRealHttp() = runBlocking {
        baseUrl()
        val alex = AuthApiClient.login("alex@example.com", "password123", "").getOrThrow()
        val alice = AuthApiClient.login("alice@example.com", "password123", "").getOrThrow()
        assertTrue("种子账号应当是 u1/u2，实际 ${alex.userId}/${alice.userId}", alex.userId == "u1" && alice.userId == "u2")

        // `ApiService` 的鉴权令牌来自 `TokenManager` 单例；直接调登录客户端不会写它，
        // 于是服务端会以 session_changed 拒绝。真实 App 也是先存会话再调这些接口的。
        val tokenManager = TokenManager.getInstance(ApplicationProvider.getApplicationContext())
        fun useSession(auth: com.maodouchat.network.AuthResponse) {
            // ApiService 还缓存了一份内存态会话；只改 TokenManager 会让它把「换了身份」
            // 判成 SESSION_CHANGED。真实 App 切换账号时也是先清内存态再存新会话。
            ApiService.clearSessionTokens()
            tokenManager.saveAuthSession(
                token = auth.token,
                refreshToken = auth.refreshToken,
                userId = auth.userId,
                accessTokenExpiresAt = auth.expiresAt,
                refreshTokenExpiresAt = auth.refreshExpiresAt,
            )
        }

        val alexProtocol = SignalProtocol(db.signalKeyDao(), db.identityTrustDao())
        val aliceProtocol = SignalProtocol(db.signalKeyDao(), db.identityTrustDao())

        useSession(alex)
        assertTrue("alex 的 Signal bootstrap 必须成功", alexProtocol.initialize(alex.token, alex.userId))
        useSession(alice)
        assertTrue("alice 的 Signal bootstrap 必须成功", aliceProtocol.initialize(alice.token, alice.userId))
        // 服务端只允许**会话参与者**之间取 prekey bundle（反枚举规则），所以必须先建会话。
        // 上一步报的「只能获取会话参与者的密钥包」就是这条规则。
        useSession(alex)
        val chat = ConversationApiClient
            .createChat(alex.token, participantIds = listOf(alice.userId), isGroup = false)
            .getOrThrow()
        assertTrue("必须先建成会话才能交换密钥，实际 chat.id=${chat.id}", chat.id.isNotBlank())

        // 回到 alex 的身份去拉 alice 的 bundle
        useSession(alex)

        // 把对端 bundle 拉回来 —— 这是服务端真的存下了上传密钥的证据。
        // 每次切换「用谁的身份调接口」都必须同步 TokenManager，否则会被判 session_changed。
        val aliceFetched = SignalKeyExchange.fetchPreKeyBundle(alex.token, alice.userId)
        useSession(alice)
        val alexFetched = SignalKeyExchange.fetchPreKeyBundle(alice.token, alex.userId)
        assertTrue(
            "必须能从服务端取到 alice 的 prekey bundle，实际失败：${aliceFetched.exceptionOrNull()}",
            aliceFetched.isSuccess,
        )
        assertTrue(
            "必须能从服务端取到 alex 的 prekey bundle，实际失败：${alexFetched.exceptionOrNull()}",
            alexFetched.isSuccess,
        )
        val aliceBundle = aliceFetched.getOrThrow()
        val alexBundle = alexFetched.getOrThrow()
        assertTrue("alice 的 identityKey 不能为空", !aliceBundle!!.identityKey.isNullOrBlank())
        assertTrue("alice 的 signedPreKey 不能为空", !aliceBundle.signedPreKey.isNullOrBlank())
        assertTrue("alex 的 identityKey 不能为空", !alexBundle!!.identityKey.isNullOrBlank())
    }
}
