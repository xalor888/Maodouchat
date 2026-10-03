package com.maodouchat.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.IncomingCallWake
import com.maodouchat.MaodouchatApp
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.webrtc.CallType
import com.maodouchat.navigation.NotificationTarget
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.telecom.TelecomHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationIntentConsumerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tokenManager: TokenManager
        get() = TokenManager.getInstance(context)

    private val selfPackage: String
        get() = context.packageName

    /** 捕获消费结果（真消费者 + 假槽位，不构造 Activity/NavController）。 */
    private val targets = mutableListOf<NotificationTarget?>()
    private val lockFlags = mutableListOf<Boolean>()

    private val consumer: NotificationIntentConsumer
        get() = NotificationIntentConsumer(
            appContext = context,
            packageName = selfPackage,
            tokenManager = tokenManager,
            setTarget = { targets += it },
            applyCallLockScreenFlags = { lockFlags += it },
        )

    @Before
    fun resetState() {
        ApiService.clearSessionTokens()
        tokenManager.clear()
        targets.clear()
        lockFlags.clear()
        IncomingCallCoordinator.clear()
        clearWakeFlows()
    }

    @After
    fun restoreLoggedOut() {
        IncomingCallCoordinator.clear()
        ApiService.clearSessionTokens()
        tokenManager.clear()
    }

    // ---- 1. 外部调用者：伪造的通知 extra 必须被清掉 --------------------------------

    @Test
    fun externalCallerNonViewIntentHasItsNotificationExtrasClearedAndDoesNotNavigate() {
        val intent = Intent("com.evil.custom.action").apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
            putExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID, "msg-9")
        }

        consumer.consume(intent, callingPackage = "com.evil.app", callingActivityPackage = null)

        assertTrue("外部调用者的通知 extra 必须清空", intent.getStringExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID) == null)
        assertTrue("外部调用者的通知 extra 必须清空", intent.getStringExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID) == null)
        assertEquals("外部调用者的非 VIEW Intent 不得产生任何目标", emptyList<NotificationTarget?>(), targets)
    }

    // ---- 2. 外部调用者的合法深链必须放行（8.34 的回归网） --------------------------

    @Test
    fun externalCallerValidActionViewDeepLinkStillOpensPublicProfile() {
        seedLoggedIn("u-owner")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.mdou.me/u/alice")).apply {
            // 同一次 Intent 里塞入伪造的通知 extra：放行深链不等于放行 extra。
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-injected")
        }

        consumer.consume(intent, callingPackage = "com.android.chrome", callingActivityPackage = null)

        val target = targets.singleOrNull() as? NotificationTarget.PublicProfile
        assertNotNull("浏览器打开 /u/{username} 必须导航到公开资料页", target)
        assertEquals("alice", target!!.username)
        assertEquals("u-owner", target.ownerUserId)
        assertNull("深链里的通知 extra 必须被清掉", intent.getStringExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID))
        assertNull("消费后不得残留 data（防复用重放）", intent.data)
    }

    @Test
    fun nonWhitelistedHostDeepLinkProducesNoNavigableTarget() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://evil.example.com/u/alice"))

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals(listOf<NotificationTarget?>(null), targets)
    }

    // ---- 3. owner 归属：与当前账号不符不得导航 ------------------------------------

    @Test
    fun chatNotificationForAnotherOwnerIsRejectedAndCleared() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-intruder")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals(listOf<NotificationTarget?>(null), targets)
        assertNull(intent.getStringExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID))
        assertNull(intent.getStringExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID))
    }

    @Test
    fun chatNotificationWithoutOwnerIsRejectedEvenWhenLoggedIn() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals(listOf<NotificationTarget?>(null), targets)
    }

    @Test
    fun chatNotificationForOwnerIsRejectedWhileLoggedOut() {
        // 登出态：没有 currentUserId，owner 无法匹配 → 不得导航。
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals(listOf<NotificationTarget?>(null), targets)
    }

    // ---- 4. 本应用通知：正常路径 + ID 清洗 ----------------------------------------

    @Test
    fun ownChatNotificationOpensChatTargetWithMessageHighlight() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
            putExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID, "msg-9")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        val target = targets.singleOrNull() as? NotificationTarget.Chat
        assertNotNull(target)
        assertEquals("chat-1", target!!.id)
        assertEquals("msg-9", target.messageId)
        assertEquals("u-owner", target.ownerUserId)
        assertNull("消费后 extras 必须清空", intent.getStringExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID))
        assertNull("消费后 extras 必须清空", intent.getStringExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID))
    }

    @Test
    fun invalidMessageIdDropsHighlightButStillOpensChat() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "chat-1")
            putExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID, "bad/msg#frag")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        val target = targets.singleOrNull() as? NotificationTarget.Chat
        assertNotNull("非法 messageId 只丢高亮，仍必须打开会话", target)
        assertEquals("chat-1", target!!.id)
        assertNull("非法 messageId 不得进入导航目标", target.messageId)
    }

    @Test
    fun invalidChatIdProducesNoTarget() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, "bad/chat#frag")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals(listOf<NotificationTarget?>(null), targets)
    }

    // ---- 5. Telecom：null caller 只认 in-process pending call ---------------------

    @Test
    fun telecomAnswerWithoutPendingCallIsRejectedAndExtrasCleared() {
        val intent = Intent(TelecomHelper.ACTION_ANSWER_CALL).apply {
            putExtra(TelecomHelper.EXTRA_CALL_ID, "forged-call")
        }

        consumer.consume(intent, callingPackage = null, callingActivityPackage = null)

        assertEquals("伪造的 Telecom 接听不得置锁屏旗标", emptyList<Boolean>(), lockFlags)
        assertEquals("伪造的 Telecom 接听不得产生目标", emptyList<NotificationTarget?>(), targets)
        assertNull("被拒的 Telecom extras 必须清空", intent.getStringExtra(TelecomHelper.EXTRA_CALL_ID))
    }

    @Test
    fun trustedTelecomAnswerEmitsAutoAnswerWakeAndSetsLockScreenFlags() {
        // 播种 in-process pending call：这是 Telecom 传输可信的唯一来源
        // （TelecomHelper.isTrustedTransport 只认 peekPending()/前台服务 callId）。
        IncomingCallCoordinator.setPending(
            IncomingCallCoordinator.PendingIncomingCall(
                contactId = "u-peer",
                contactName = "Peer",
                callType = CallType.VIDEO,
                offerSdp = "v=0",
                callId = "call-9",
            ),
        )
        val intent = Intent(TelecomHelper.ACTION_ANSWER_CALL).apply {
            putExtra(TelecomHelper.EXTRA_CALL_ID, "call-9")
            putExtra(TelecomHelper.EXTRA_IS_VIDEO, true)
        }

        consumer.consume(intent, callingPackage = null, callingActivityPackage = null)

        assertEquals("可信接听必须置锁屏旗标", listOf(true), lockFlags)
        val wake = latestWake()
        assertNotNull("可信接听必须发出来电唤醒", wake)
        assertEquals("call-9", wake!!.callId)
        assertTrue("8.56：系统 Telecom 接听必须标记自动接听（应用内不再二次点击）", wake.autoAnswer)
        assertTrue("视频标志必须透传", wake.isVideo)
        assertNull("被消费的 Telecom extras 必须清空", intent.getStringExtra(TelecomHelper.EXTRA_CALL_ID))
    }

    @Test
    fun trustedTelecomIncomingCallTransportWakesWithoutAutoAnswer() {
        IncomingCallCoordinator.setPending(
            IncomingCallCoordinator.PendingIncomingCall(
                contactId = "u-peer",
                contactName = "Peer",
                callType = CallType.AUDIO,
                offerSdp = "v=0",
                callId = "call-10",
            ),
        )
        val intent = Intent(TelecomHelper.ACTION_INCOMING_CALL).apply {
            putExtra(TelecomHelper.EXTRA_CALL_ID, "call-10")
        }

        consumer.consume(intent, callingPackage = null, callingActivityPackage = null)

        val wake = latestWake()
        assertNotNull(wake)
        assertEquals("call-10", wake!!.callId)
        assertFalse("拉起 ≠ 接听：不得标记自动接听", wake.autoAnswer)
    }

    @Test
    fun telecomAnswerWithMismatchedPendingCallIdIsStillRejected() {
        IncomingCallCoordinator.setPending(
            IncomingCallCoordinator.PendingIncomingCall(
                contactId = "u-peer",
                contactName = "Peer",
                callType = CallType.AUDIO,
                offerSdp = "v=0",
                callId = "call-A",
            ),
        )
        val intent = Intent(TelecomHelper.ACTION_ANSWER_CALL).apply {
            putExtra(TelecomHelper.EXTRA_CALL_ID, "call-B")
        }

        consumer.consume(intent, callingPackage = null, callingActivityPackage = null)

        assertEquals("错配的 callId 不得置锁屏旗标", emptyList<Boolean>(), lockFlags)
        assertNull("错配的 callId 不得发出来电唤醒", latestWake())
        assertNull(intent.getStringExtra(TelecomHelper.EXTRA_CALL_ID))
    }

    // ---- 6. 来电/未接通知：唤醒流与清洗 -------------------------------------------

    @Test
    fun incomingCallNotificationEmitsWakeWithSanitizedCallId() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_INCOMING_CALL, true)
            putExtra(NotificationIntents.EXTRA_INCOMING_CALL_ID, "call-1")
            putExtra(NotificationIntents.EXTRA_INCOMING_CALL_SENDER_ID, "user-7")
            putExtra(NotificationIntents.EXTRA_INCOMING_CALL_VIDEO, true)
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        assertEquals("来电入口必须请求锁屏旗标", listOf(true), lockFlags)
        val wake = latestWake()
        assertNotNull("必须发出来电唤醒", wake)
        assertEquals("call-1", wake!!.callId)
        assertEquals("user-7", wake.senderId)
        assertTrue(wake.isVideo)
    }

    @Test
    fun incomingCallWithPathologicalCallIdFallsBackToEmptyForGenericPolling() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_INCOMING_CALL, true)
            putExtra(NotificationIntents.EXTRA_INCOMING_CALL_ID, "bad/call#id")
            putExtra(NotificationIntents.EXTRA_INCOMING_CALL_SENDER_ID, "bad/sender")
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        val wake = latestWake()
        assertNotNull(wake)
        assertEquals("非法 callId 必须降级为空串（走通用轮询，不定向响铃）", "", wake!!.callId)
        assertEquals("非法 senderId 必须降级为空串", "", wake.senderId)
    }

    @Test
    fun missedCallNotificationEmitsOpenRequest() {
        seedLoggedIn("u-owner")
        val intent = Intent().apply {
            putExtra(NotificationIntents.EXTRA_OPEN_MISSED_CALL, true)
            putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, "u-owner")
        }

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        val request = runBlocking {
            withTimeoutOrNull(2_000) { MaodouchatApp.openMissedCallsEvents.first() }
        }
        assertNotNull("未接来电入口必须发出打开请求", request)
    }

    // ---- 7. 邀请深链（生产冒号协议） ----------------------------------------------

    @Test
    fun chatInviteColonLinkOpensGroupInviteTarget() {
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq" // 42 chars，Base64URL 形状
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${com.maodouchat.navigation.AppLinkRouter.CHAT_INVITE_COLON_PREFIX}$token"))

        consumer.consume(intent, callingPackage = selfPackage, callingActivityPackage = null)

        val target = targets.singleOrNull() as? NotificationTarget.GroupInvite
        assertNotNull("生产邀请协议必须导航到群邀请目标", target)
        assertEquals(token, target!!.code)
        assertNull("消费后不得残留 data", intent.data)
    }

    // ---- helpers ----------------------------------------------------------------

    private fun seedLoggedIn(userId: String) {
        val now = System.currentTimeMillis()
        val saved = tokenManager.saveAuthSession(
            token = "test-token",
            refreshToken = "test-refresh",
            userId = userId,
            accessTokenExpiresAt = now + 3_600_000L,
            refreshTokenExpiresAt = now + 30L * 24 * 3_600_000L,
        )
        check(saved) { "测试前置失败：会话未写入 TokenManager" }
    }

    private fun latestWake(): IncomingCallWake? = runBlocking {
        withTimeoutOrNull(2_000) { MaodouchatApp.incomingCallWakeEvents.first() }
    }

    private fun clearWakeFlows() {
        runBlocking {
            withTimeoutOrNull(200) { MaodouchatApp.incomingCallWakeEvents.first() }
                ?.let { MaodouchatApp.consumeIncomingCallWake(it) }
            withTimeoutOrNull(200) { MaodouchatApp.openMissedCallsEvents.first() }
                ?.let { MaodouchatApp.consumeOpenMissedCalls(it) }
            withTimeoutOrNull(200) { MaodouchatApp.openContactsEvents.first() }
                ?.let { MaodouchatApp.consumeOpenContacts(it) }
        }
    }
}
