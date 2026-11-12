package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.IdentityTrustState
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityVerificationControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))
    private val groupChat = Chat(id = "c1", participants = listOf(me, peer), isGroup = true, memberRevision = 7L)

    private fun device(
        deviceId: Int,
        trustState: IdentityTrustState,
        safetyCode: String? = "code-$deviceId",
        fingerprint: String = "fp-$deviceId",
    ) = DeviceSafetyState(
        deviceId = deviceId,
        identityKey = "key-$deviceId",
        identityFingerprint = fingerprint,
        trustState = trustState,
        safetyCode = safetyCode,
    )

    private inner class Harness(
        initial: ChatDetailUiState,
        private val dispatcher: CoroutineDispatcher,
    ) {
        val uiState = MutableStateFlow(initial)
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        var currentUserId = "owner-1"
        var token: String? = "tok"
        var sessionMayContinue = true
        var safetyCodeEnabled = true
        var deviceVerifyEnabled = true
        var safetyStatesResult: Result<List<DeviceSafetyState>> = Result.success(emptyList())
        var markVerifiedResult = true
        val safetyCalls = mutableListOf<Pair<String, String>>()
        val markCalls = mutableListOf<Pair<String, Int>>()
        val verifiedFingerprints = mutableListOf<String>()
        var resetCalls = 0

        fun controller() = IdentityVerificationController(
            scope = scope,
            resetDirectIdentityForGroup = { resetCalls++; it.copy(identityWarning = "reset") },
            uiState = uiState,
            text = { key -> key.name },
            currentUserId = { currentUserId },
            sessionToken = { token },
            sessionMayContinue = { sessionMayContinue },
            safetyCodeEnabled = { safetyCodeEnabled },
            deviceVerifyEnabled = { deviceVerifyEnabled },
            markFingerprintVerified = { fp -> verifiedFingerprints += fp },
            getRemoteDeviceSafetyStates = { liveToken, contactId ->
                safetyCalls += liveToken to contactId
                safetyStatesResult
            },
            markIdentityVerified = { contactId, deviceId ->
                markCalls += contactId to deviceId
                markVerifiedResult
            },
            ioDispatcher = dispatcher,
        )

        suspend fun settle() {
            var guard = 0
            while (scopeJob.children.any { it.isActive } && guard++ < 10) {
                scopeJob.children.forEach { it.join() }
            }
        }
    }

    private fun state(
        chat: Chat? = this.chat,
        contact: User = peer,
        deviceSafetyStates: List<DeviceSafetyState> = emptyList(),
    ) = ChatDetailUiState(
        contact = contact,
        chat = chat,
        deviceSafetyStates = deviceSafetyStates,
    )

    @Test
    fun refreshLoadsTrustedStates() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.safetyStatesResult = Result.success(
            listOf(device(1, IdentityTrustState.TRUSTED), device(2, IdentityTrustState.VERIFIED))
        )
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        val value = harness.uiState.value
        assertEquals(listOf("tok" to "u2"), harness.safetyCalls)
        assertEquals("IDENTITY_TRUSTED_WARNING", value.identityWarning)
        assertEquals("code-1", value.safetyCode)
        assertEquals("fp-1", value.contactIdentityFingerprint)
        assertEquals(IdentityTrustState.TRUSTED, value.trustState)
        assertEquals(2, value.deviceSafetyStates.size)
        assertFalse(value.isLoadingDeviceSafety)
        assertNull(value.deviceSafetyWarning)
        assertTrue(value.canVerifyIdentity)
    }

    @Test
    fun refreshChangedWarningWins() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.safetyStatesResult = Result.success(
            listOf(device(1, IdentityTrustState.VERIFIED), device(2, IdentityTrustState.CHANGED))
        )
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        assertEquals("IDENTITY_CHANGED_WARNING", harness.uiState.value.identityWarning)
        assertEquals(IdentityTrustState.CHANGED, harness.uiState.value.trustState)
    }

    @Test
    fun refreshEmptyStatesIsUnknown() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        val value = harness.uiState.value
        assertEquals("IDENTITY_UNKNOWN_WARNING", value.identityWarning)
        assertEquals(IdentityTrustState.UNKNOWN, value.trustState)
        assertNull(value.safetyCode)
        assertNull(value.contactIdentityFingerprint)
        assertFalse(value.canVerifyIdentity)
    }

    @Test
    fun refreshFailureShowsWarning() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.safetyStatesResult = Result.failure(RuntimeException("boom"))
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        val value = harness.uiState.value
        assertEquals("SAFETY_DEVICES_LOAD_FAILED", value.deviceSafetyWarning)
        assertFalse(value.isLoadingDeviceSafety)
    }

    @Test
    fun refreshGroupChatResetsDirectIdentity() = runTest(dispatcher) {
        val harness = Harness(state(chat = groupChat), dispatcher)
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        assertEquals(1, harness.resetCalls)
        assertEquals("reset", harness.uiState.value.identityWarning)
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun refreshBlankContactSkips() = runTest(dispatcher) {
        val harness = Harness(state(contact = User(id = "", name = "")), dispatcher)
        harness.controller().refreshIdentitySafetyState("")
        harness.settle()
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun refreshGateClosedSkips() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.sessionMayContinue = false
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun refreshMeUserSkips() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.currentUserId = "me"
        harness.controller().refreshIdentitySafetyState("u2")
        harness.settle()
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun showSafetyCodeDialogDisabledShowsError() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.safetyCodeEnabled = false
        harness.controller().showSafetyCodeDialog()
        harness.settle()
        assertEquals("SAFETY_CODE_DISABLED", harness.uiState.value.errorMessage)
        assertFalse(harness.uiState.value.showSafetyCodeDialog)
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun showSafetyCodeDialogEnabledLoadsStates() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().showSafetyCodeDialog()
        harness.settle()
        assertTrue(harness.uiState.value.showSafetyCodeDialog)
        assertEquals(1, harness.safetyCalls.size)
    }

    @Test
    fun dismissSafetyCodeDialog() = runTest(dispatcher) {
        val harness = Harness(state().copy(showSafetyCodeDialog = true), dispatcher)
        harness.controller().dismissSafetyCodeDialog()
        harness.settle()
        assertFalse(harness.uiState.value.showSafetyCodeDialog)
    }

    @Test
    fun verifySuccessRefreshesAndMarksFingerprint() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.safetyStatesResult = Result.success(listOf(device(1, IdentityTrustState.VERIFIED)))
        harness.controller().verifyAndTrustIdentity()
        harness.settle()
        assertEquals(listOf("u2" to 1), harness.markCalls)
        assertEquals(1, harness.safetyCalls.size)
        assertEquals(listOf("fp-1"), harness.verifiedFingerprints)
        assertNull(harness.uiState.value.deviceSafetyWarning)
    }

    @Test
    fun verifyFailureShowsWarning() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.markVerifiedResult = false
        harness.controller().verifyAndTrustIdentity(deviceId = 2)
        harness.settle()
        assertEquals(listOf("u2" to 2), harness.markCalls)
        assertEquals("TRUST_FAILED", harness.uiState.value.deviceSafetyWarning)
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun verifyAllDevicesSuccessRefreshes() = runTest(dispatcher) {
        val harness = Harness(
            state(deviceSafetyStates = listOf(device(1, IdentityTrustState.TRUSTED), device(2, IdentityTrustState.TRUSTED))),
            dispatcher,
        )
        harness.controller().verifyAllDevices()
        harness.settle()
        assertEquals(listOf("u2" to 1, "u2" to 2), harness.markCalls)
        assertEquals(1, harness.safetyCalls.size)
        assertNull(harness.uiState.value.deviceSafetyWarning)
    }

    @Test
    fun verifyAllDevicesPartialFailureShowsWarning() = runTest(dispatcher) {
        val harness = Harness(
            state(deviceSafetyStates = listOf(device(1, IdentityTrustState.TRUSTED), device(2, IdentityTrustState.TRUSTED))),
            dispatcher,
        )
        harness.markVerifiedResult = false
        harness.controller().verifyAllDevices()
        harness.settle()
        assertEquals(listOf("u2" to 1, "u2" to 2), harness.markCalls)
        assertEquals("TRUST_FAILED", harness.uiState.value.deviceSafetyWarning)
        assertTrue(harness.safetyCalls.isEmpty())
    }

    @Test
    fun verifyAllDevicesEmptySkips() = runTest(dispatcher) {
        val harness = Harness(state(), dispatcher)
        harness.controller().verifyAllDevices()
        harness.settle()
        assertTrue(harness.markCalls.isEmpty())
    }
}
