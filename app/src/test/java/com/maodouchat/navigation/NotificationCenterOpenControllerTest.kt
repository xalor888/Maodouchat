package com.maodouchat.navigation

import com.maodouchat.data.repository.NotificationCenterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationCenterOpenControllerTest {

    private class Recorder {
        val clearedMessages = mutableListOf<String>()
        val clearedMissedCalls = mutableListOf<String>()
        val clearedAiReminders = mutableListOf<String>()
        val clearedPostInteractions = mutableListOf<String>()
        val markedPostsRead = mutableListOf<String>()
        var missedCallsEmits = 0
        var contactsEmits = 0

        fun controller(
            hasSessionToken: Boolean = true,
        ) = NotificationCenterOpenController(
            hasSessionToken = { hasSessionToken },
            clearMessageTray = { clearedMessages += it },
            clearMissedCallTray = { clearedMissedCalls += it },
            clearAiTaskReminders = { clearedAiReminders += it },
            clearPostInteractionTray = { clearedPostInteractions += it },
            markPostInteractionsRead = { markedPostsRead += it },
            emitOpenMissedCalls = { missedCallsEmits += 1 },
            emitOpenContacts = { contactsEmits += 1 },
        )

        /** 托盘清理/标记已读全部未发生（不检查 emit 计数——有的用例就该 emit）。 */
        fun assertNoTraySideEffects() {
            assertTrue(clearedMessages.isEmpty())
            assertTrue(clearedMissedCalls.isEmpty())
            assertTrue(clearedAiReminders.isEmpty())
            assertTrue(clearedPostInteractions.isEmpty())
            assertTrue(markedPostsRead.isEmpty())
        }

        fun assertNoSideEffects() {
            assertNoTraySideEffects()
            assertEquals(0, missedCallsEmits)
            assertEquals(0, contactsEmits)
        }
    }

    private fun item(
        type: String,
        deeplink: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = NotificationCenterItem(
        id = "n1",
        type = type,
        mergeKey = "k1",
        title = "t",
        deeplink = deeplink,
        extra = extra,
    )

    @Test
    fun loggedOutSendsUserToLoginWithoutSideEffects() {
        val rec = Recorder()
        val outcome = rec.controller(hasSessionToken = false).onOpenItem(item("MESSAGE"))
        assertEquals(NotificationCenterOpenController.Outcome.GoLogin, outcome)
        rec.assertNoSideEffects()
    }

    @Test
    fun messageRowOpensChatAndClearsItsTray() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MESSAGE", extra = mapOf("chatId" to "c-1")))
        assertEquals(Routes.chatDetail("c-1"), (outcome as NotificationCenterOpenController.Outcome.Navigate).route)
        assertEquals(listOf("c-1"), rec.clearedMessages)
    }

    @Test
    fun messageRowWithoutChatIdDoesNothing() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MESSAGE"))
        assertEquals(NotificationCenterOpenController.Outcome.None, outcome)
        rec.assertNoSideEffects()
    }

    @Test
    fun missedCallRowClearsTrayByCallIdAndOpensInbox() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MISSED_CALL", extra = mapOf("callId" to "call-1")))
        assertEquals(NotificationCenterOpenController.Outcome.PopBackStack, outcome)
        assertEquals(listOf("call-1"), rec.clearedMissedCalls)
        assertEquals(1, rec.missedCallsEmits)
    }

    @Test
    fun missedCallRowWithoutCallIdStillOpensInbox() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MISSED_CALL"))
        assertEquals(NotificationCenterOpenController.Outcome.PopBackStack, outcome)
        assertTrue(rec.clearedMissedCalls.isEmpty())
        assertEquals(1, rec.missedCallsEmits)
    }

    @Test
    fun friendRequestRowOpensContacts() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("FRIEND_REQUEST"))
        assertEquals(NotificationCenterOpenController.Outcome.PopBackStack, outcome)
        assertEquals(1, rec.contactsEmits)
        rec.assertNoTraySideEffects()
    }

    @Test
    fun chatDeeplinkOpensChatAndClearsItsTray() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MESSAGE", deeplink = "maodouchat:chat:c-9"))
        assertEquals(Routes.chatDetail("c-9"), (outcome as NotificationCenterOpenController.Outcome.Navigate).route)
        assertEquals(listOf("c-9"), rec.clearedMessages)
    }

    @Test
    fun aiTasksDeeplinkClearsRemindersAndOpensTasks() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("AI_TASK", deeplink = "maodouchat:ai_tasks:c-7"))
        assertEquals(Routes.aiTasks("c-7"), (outcome as NotificationCenterOpenController.Outcome.Navigate).route)
        assertEquals(listOf("c-7"), rec.clearedAiReminders)
    }

    @Test
    fun postDeeplinkClearsTrayAndMarksInteractionsRead() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("POST_INTERACTION", deeplink = "maodouchat:post:p-3"))
        assertEquals(Routes.postDetail("p-3"), (outcome as NotificationCenterOpenController.Outcome.Navigate).route)
        assertEquals(listOf("p-3"), rec.clearedPostInteractions)
        assertEquals(listOf("p-3"), rec.markedPostsRead)
    }

    @Test
    fun missedCallsTabDeeplinkOpensInbox() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(
            item("MISSED_CALL", deeplink = "maodouchat:missed_calls", extra = mapOf("callId" to "call-2")),
        )
        assertEquals(NotificationCenterOpenController.Outcome.PopBackStack, outcome)
        assertEquals(listOf("call-2"), rec.clearedMissedCalls)
        assertEquals(1, rec.missedCallsEmits)
    }

    @Test
    fun contactsTabDeeplinkOpensContacts() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("FRIEND_REQUEST", deeplink = "maodouchat:contacts"))
        assertEquals(NotificationCenterOpenController.Outcome.PopBackStack, outcome)
        assertEquals(1, rec.contactsEmits)
    }

    @Test
    fun unparsableDeeplinkDoesNothing() {
        val rec = Recorder()
        val outcome = rec.controller().onOpenItem(item("MESSAGE", deeplink = "https://example.com/x"))
        assertEquals(NotificationCenterOpenController.Outcome.None, outcome)
        rec.assertNoSideEffects()
    }
}
