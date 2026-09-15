package com.maodouchat.e2e

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.security.LogoutStorePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * **本地数据的生命周期与备份面**（G37）。
 *
 * 这个类**故意独立于** `TwoAccountHttpRoundTripTest`：本类里的换号清理用例会**真的销毁并重建**
 * 进程内的 app 数据库（`SecureSessionManager` 在 destroy 分支后经 `onEncryptedDatabaseDestroyed`
 * 触发 `rebuildLocalStorage()`），放在主类里会影响其余 22 个用例。
 *
 * 分层说明：
 * - 策略层（纯函数）与备份面（`PackageManager`/`ApplicationInfo`）是**运行时**证据；
 * - 「**不存在应用内备份/恢复功能**」是**静态**结论（无 UI 入口、无数据备份 API），
 *   只能在台账里以静态证据说明，**不作为**运行时证据。
 */
@RunWith(AndroidJUnit4::class)
class ClientDataLifecycleTest {

    @Before
    fun requireE2eServer() {
        val enabled = InstrumentationRegistry.getArguments().getString("e2eHttp")
        org.junit.Assume.assumeTrue(
            "需要真服务端：请用 scripts/two-device-http-e2e.sh 运行（会注入 e2eHttp=1 与服务端地址）",
            enabled == "1",
        )
    }

    /**
     * ① 登出/换号的去留是**显式策略**，且派生策略必须与主策略一致。
     *
     * 这条断言同时把设计语义写成可执行事实：**同账号登出保留加密库**（重登要能解密历史）、
     * **换号/删号/信任域变更毁库**。
     */
    @Test
    fun logoutStorePolicyIsExplicitAndConsistent() {
        assertFalse(
            "同账号登出必须**保留**加密库（重登要能解密历史）",
            LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.LOGOUT),
        )
        assertFalse(
            "token 过期同样保留",
            LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.TOKEN_EXPIRED),
        )
        assertTrue(
            "换号必须毁库",
            LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.ACCOUNT_SWITCH),
        )
        assertTrue(
            "删号必须毁库",
            LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.DELETE_ACCOUNT),
        )
        assertTrue(
            "信任域变更必须毁库",
            LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.TRUST_DOMAIN_CHANGE),
        )

        // 派生策略必须与主策略一致：不一致会让「保留库但清了缓存」这类组合出现。
        LogoutStorePolicy.Reason.entries.forEach { reason ->
            val expected = LogoutStorePolicy.destroyEncryptedDatabase(reason)
            assertEquals(
                "普通媒体缓存的清理策略必须与主策略一致（reason=$reason）",
                expected,
                LogoutStorePolicy.wipeOrdinaryMediaCache(reason),
            )
            assertEquals(
                "Coil 磁盘缓存的清理策略必须与主策略一致（reason=$reason）",
                expected,
                LogoutStorePolicy.wipeCoilDiskCache(reason),
            )
            assertEquals(
                "in-flight 附件传输的清理策略必须与主策略一致（reason=$reason）",
                expected,
                LogoutStorePolicy.wipeInFlightAttachmentTransfers(reason),
            )
        }
    }

    /** ② 备份面在**已安装**应用上的取值（不是照抄 manifest 源码）。 */
    @Test
    fun backupSurfaceIsClosedAtRuntime() {
        val ctx = ApplicationProvider.getApplicationContext<MaodouchatApp>()
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)

        assertTrue(
            "已安装应用必须关闭 allowBackup（FLAG_ALLOW_BACKUP=0），实际 flags=${info.flags}",
            (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0,
        )

        assertNull(
            "不得声明自定义备份代理（backupAgentName 必须为空，否则备份面可能被它绕过），实际=${info.backupAgentName}",
            info.backupAgentName,
        )

        // 直接解析**已安装 APK** 里的排除规则资源 —— 这是运行时读取，不是照抄 manifest 源码。
        val parser = ctx.resources.getXml(com.maodouchat.R.xml.data_extraction_rules)
        val pairs = mutableSetOf<String>()
        var section = ""
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> {
                        val domain = parser.getAttributeValue(null, "domain")
                        if (domain != null) pairs += "$section/$domain"
                    }
                }
            }
            event = parser.next()
        }
        assertTrue("排除规则资源必须解析出条目，实际=$pairs", pairs.isNotEmpty())
        listOf("database", "sharedpref", "file").forEach { domain ->
            assertTrue(
                "cloud-backup 必须排除 $domain（否则数据库/偏好/文件会进云备份），实际=$pairs",
                pairs.contains("cloud-backup/$domain"),
            )
            assertTrue(
                "device-transfer 必须排除 $domain（否则换机会把明文搬过去），实际=$pairs",
                pairs.contains("device-transfer/$domain"),
            )
        }
    }

    /**
     * ③ **换号清理必须真的清掉本地明文**（真代码 + 真库）。
     *
     * 夹具说明：这里用生产的 `LocalMessageStore.insertMessage` 把带标记的消息写进**真实** app 库
     * （而不是走 G32/G34 的投影器路径）——本用例的被测对象是**清理**，投影路径已由 G32/G34 覆盖。
     */
    @Test
    fun accountSwitchPurgeRemovesLocalPlaintext() {
        val ctx = ApplicationProvider.getApplicationContext<MaodouchatApp>()
        val app = ctx
        val owner = "purge-owner-${UUID.randomUUID().toString().take(8)}"
        val chatId = "purge-chat-${UUID.randomUUID()}"
        val messageId = "purge-msg-${UUID.randomUUID()}"
        val marker = "purge-plaintext-${UUID.randomUUID()}"

        val store = LocalMessageStore(ctx.database.messageDao(), ctx.database)
        runBlocking {
            ctx.database.chatDao().insertChats(
                listOf(ChatEntity(id = chatId, isGroup = false, chatType = "DIRECT", participantIds = owner)),
            )
            store.insertMessage(
                Message(id = messageId, chatId = chatId, senderId = owner, content = "正文 $marker"),
            )
        }

        // 控制组：标记必须真的在库里，否则「清掉后读不到」是空断言。
        val before = runBlocking { ctx.database.messageDao().getMessageById(messageId) }
        assertTrue("控制组失败：标记必须真的写进本地库，实际=$before", before?.content?.contains(marker) == true)

        // 按**生产策略**决定是否毁库（换号 = true），再跑**生产**清理。
        val destroy = LogoutStorePolicy.destroyEncryptedDatabase(LogoutStorePolicy.Reason.ACCOUNT_SWITCH)
        assertTrue("换号策略必须是毁库，否则本条用例测不到清理", destroy)
        val purged = runBlocking {
            app.secureSessionManager.purgeLocalSession(
                destroyEncryptedDatabase = destroy,
                expectedOwnerUserId = null,
            )
        }
        assertTrue("清理必须报告成功，实际=$purged", purged)

        // 换号之后：该消息必须读不到（库被销毁并重建为空）。
        val after = runCatching { runBlocking { ctx.database.messageDao().getMessageById(messageId) } }
        assertNull(
            "换号清理之后本地明文必须读不到，实际=${after.getOrNull()}（异常=${after.exceptionOrNull()}）",
            after.getOrNull(),
        )
    }
}
