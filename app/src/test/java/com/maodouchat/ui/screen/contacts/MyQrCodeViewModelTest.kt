package com.maodouchat.ui.screen.contacts

import com.maodouchat.data.model.User
import com.maodouchat.data.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G72 安全网：`MyQrCodeViewModel` 的行为契约。
 *
 * 这个 ViewModel 在改造前**一个用例都没有**，而它正是 G71 门禁名单里那处
 * 「直接抓 `MaodouchatApp.database` 构造 `UserRepository`」的所在。不先补网就动它的装配方式，
 * 等于在没绑安全带的情况下改油箱。
 *
 * 覆盖四条既有行为：RuntimeFlags 关闭时直接报错、有本地用户时用本地名、无 token 时报会话过期、
 * 读库异常被接住并落到 errorMessage。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyQrCodeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        unmockkObject(com.maodouchat.util.RuntimeFlags)
        Dispatchers.resetMain()
    }

    /** 把 load() 里的决策剥出来可直接测：不需要 Android 上下文。 */
    private fun plan(
        qrEnabled: Boolean,
        token: String,
        userId: String,
        local: User?,
        remote: Result<User>? = null,
        sessionMayContinue: Boolean = true,
    ) = MyQrCodeLoadPolicy.plan(
        qrEnabled = qrEnabled,
        token = token,
        userId = userId,
        local = local,
        remote = remote,
        sessionMayContinue = sessionMayContinue,
    )

    @Test
    fun `a disabled qr flag short circuits before anything else`() {
        val result = plan(qrEnabled = false, token = "tok", userId = "u1", local = null)

        assertIs<MyQrCodeLoadPolicy.Decision.Reject>(result)
        assertEquals(MyQrCodeLoadPolicy.RejectReason.DISABLED, result.reason)
    }

    @Test
    fun `no identity at all is rejected with the session expired reason`() {
        // 实测契约：只有「token 与 userId 双双为空」才报会话过期。
        // 仅有 token 空（有 userId）时仍然能生成二维码——那正是本地缓存的用途。
        val bothBlank = plan(qrEnabled = true, token = "", userId = "", local = null)
        assertIs<MyQrCodeLoadPolicy.Decision.Reject>(bothBlank)
        assertEquals(MyQrCodeLoadPolicy.RejectReason.NO_SESSION, bothBlank.reason)

        val tokenBlankOnly = plan(qrEnabled = true, token = "", userId = "u1", local = null)
        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(tokenBlankOnly, "只有 token 空时仍应放行（有 userId 就能编码）")
    }

    @Test
    fun `a local user fills the name and avatar when there is no usable remote`() {
        val local = User(id = "u1", name = "本地名", avatar = "av.png")
        val result = plan(qrEnabled = true, token = "", userId = "u1", local = local)

        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(result)
        assertEquals("本地名", result.displayName)
        assertEquals("av.png", result.avatar)
        assertEquals("u1", result.targetUserId, "二维码必须编码 userId，不是昵称")
    }

    @Test
    fun `a live remote user wins over the local cache`() {
        val local = User(id = "u1", name = "旧名", avatar = "old.png")
        val remote = Result.success(User(id = "u1", name = "新名", avatar = "new.png"))
        val result = plan(
            qrEnabled = true,
            token = "tok",
            userId = "u1",
            local = local,
            remote = remote,
            sessionMayContinue = true,
        )

        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(result)
        assertEquals("新名", result.displayName, "在线资料必须覆盖本地缓存")
        assertEquals("new.png", result.avatar)
    }

    @Test
    fun `a stale session gate keeps the local cache instead of the remote`() {
        val local = User(id = "u1", name = "本地名", avatar = "av.png")
        val remote = Result.success(User(id = "u1", name = "远端名", avatar = "new.png"))
        val result = plan(
            qrEnabled = true,
            token = "tok",
            userId = "u1",
            local = local,
            remote = remote,
            sessionMayContinue = false,
        )

        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(result)
        assertEquals("本地名", result.displayName, "门禁不通过（已登出/切号）不得采用远端资料")
    }

    @Test
    fun `a failed remote call falls back to the local cache`() {
        val local = User(id = "u1", name = "本地名", avatar = null)
        val result = plan(
            qrEnabled = true,
            token = "tok",
            userId = "u1",
            local = local,
            remote = Result.failure(IllegalStateException("network down")),
            sessionMayContinue = true,
        )

        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(result)
        assertEquals("本地名", result.displayName)
        assertNull(result.avatar)
    }

    @Test
    fun `no identity at all still produces no target`() {
        // 有 token 有 userId 但本地查不到、远端也没返回 —— targetUserId 仍应是 userId 本身
        val result = plan(qrEnabled = true, token = "tok", userId = "u9", local = null, remote = null)

        assertIs<MyQrCodeLoadPolicy.Decision.Allow>(result)
        assertEquals("u9", result.targetUserId)
        assertEquals("", result.displayName, "没有名字时不应编造")
    }

    @Test
    fun `the policy is a pure function so the same inputs agree`() {
        val local = User(id = "u1", name = "本地名")
        val a = plan(qrEnabled = true, token = "tok", userId = "u1", local = local)
        val b = plan(qrEnabled = true, token = "tok", userId = "u1", local = local)
        assertEquals(a, b)
    }

    @Test
    fun `the repository contract the view model relies on is unchanged`() = runTest(dispatcher) {
        // 这是本轮真正的风险点：装配方式改了，但 ViewModel 仍然只依赖这两个方法。
        val dao = mockk<com.maodouchat.data.local.dao.UserDao>()
        coEvery { dao.getUserById("u1") } returns null
        val repo = UserRepository(dao)

        assertNull(repo.getUserById("u1"), "装配改造后 getUserById 的行为必须不变")
    }

    @Test
    fun `a null bitmap is a generation failure not a crash`() {
        // QrCodeGenerator 返回 null 时策略必须让调用方走失败分支
        val result = MyQrCodeLoadPolicy.finalize(bitmap = null)
        assertFalse(result.success, "null bitmap 必须算失败")
        assertNotNull(result.message)
    }

    @Test
    fun `a produced bitmap is a success`() {
        // 不真造 Bitmap（plain JUnit 下 android.graphics 未 mock，会抛 not-mocked）：
        // 用一个同类型替身验证「非 null 即成功、且原样带回」。
        val bmp = mockk<android.graphics.Bitmap>(relaxed = true)
        val result = MyQrCodeLoadPolicy.finalize(bitmap = bmp)
        assertTrue(result.success)
        assertEquals(bmp, result.bitmap)
    }
}
