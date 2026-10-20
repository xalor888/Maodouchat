package com.maodouchat.crypto

import com.maodouchat.core.crypto.DecryptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptHistoryPolicyTest {

    @Test
    fun recoverableFailuresKeepWireIncludingNotForThisDevice() {
        val keep = listOf(
            DecryptResult.NotForThisDevice,
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
        )
        keep.forEach { result ->
            assertTrue(result.toString(), DecryptHistoryPolicy.shouldKeepWire(result))
        }
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("ok")))
    }

    @Test
    fun sessionRepairIsDeferredAndDeduped() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Failed))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("", emptySet()))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf("u2")))
    }

    @Test
    fun trueWipeWithoutRestoredIdentityCannotDecryptCloudHistory() {
        assertTrue(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(identityRestoredFromStore = false))
        assertFalse(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(identityRestoredFromStore = true))
    }

    @Test
    fun deferSessionRepairIsNarrowToNoSessionAndUntrustedIdentity() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        listOf(
            DecryptResult.NotForThisDevice,
            DecryptResult.FutureEpoch,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
            DecryptResult.Success("x"),
        ).forEach { result ->
            assertFalse(result.toString(), DecryptHistoryPolicy.shouldDeferSessionRepair(result))
        }
    }

    @Test
    fun sessionRepairAttemptTrimsWhitespaceBeforeDedup() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("  u2  ", emptySet()))
        // trim 先于去重：带空格的 u2 仍命中已尝试集合
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair(" u2 ", setOf("u2")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\t", emptySet()))
    }

    @Test
    fun sessionRepairAttemptDedupesPerSender() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf("u1", "u3")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u1", setOf("u1", "u3")))
    }

    @Test
    fun sessionRepairAttemptSenderIdIsCaseSensitive() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("U2", setOf("u2")))
    }

    @Test
    fun emptySuccessPayloadStillDropsWire() {
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("")))
    }

    @Test
    fun unsupportedEnvelopeKeepsWireWithoutDeferringRepair() {
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.UnsupportedEnvelope))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UnsupportedEnvelope))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Duplicate))
    }

    @Test
    fun failedAndDuplicateKeepWireButAreNotRepairSignals() {
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Failed))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Duplicate))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Failed))
    }

    @Test
    fun whitespaceOnlySenderIdsNeverAttemptRepair() {
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("   ", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\n\t", emptySet()))
    }

    @Test
    fun repairAttemptIsDeterministicForRepeatedCalls() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u9", emptySet()))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u9", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u9", setOf("u9")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u9", setOf("u9")))
    }

    @Test
    fun futureEpochKeepsWireButNeverTriggersRepair() {
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.FutureEpoch))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.FutureEpoch))
    }

    @Test
    fun repairAttemptRequiresNormalizedDedupKeys() {
        // 只有输入被 trim，去重集合的键按原样比对：调用方必须自己存归一化后的 id
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf(" u2 ")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf("u2", " u3 ")))
    }

    @Test
    fun repairAttemptTrimsControlWhitespace() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2\n", emptySet()))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("\t u2 \r\n", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\n \t", emptySet()))
    }

    @Test
    fun successNeverKeepsWireRegardlessOfPayload() {
        // 保线只看结果类型，正文内容不影响决策
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("Failed")))
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("  ")))
    }

    @Test
    fun repairableFailuresKeepWireAndDeferTogether() {
        listOf(DecryptResult.NoSession, DecryptResult.UntrustedIdentity).forEach { result ->
            assertTrue(result.toString(), DecryptHistoryPolicy.shouldKeepWire(result))
            assertTrue(result.toString(), DecryptHistoryPolicy.shouldDeferSessionRepair(result))
        }
    }

    @Test
    fun blankSenderNeverAttemptsEvenIfSetContainsIt() {
        // 空白先于去重被拒，集合里有没有它都不尝试
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("  ", setOf("  ")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("", setOf("")))
    }

    @Test
    fun keepAndDeferFormAnExhaustiveCrossTable() {
        // 八种结果 × 两个决策，一张表钉死
        val table = mapOf(
            DecryptResult.Success("x") to (false to false),
            DecryptResult.NotForThisDevice to (true to false),
            DecryptResult.NoSession to (true to true),
            DecryptResult.UntrustedIdentity to (true to true),
            DecryptResult.FutureEpoch to (true to false),
            DecryptResult.Failed to (true to false),
            DecryptResult.Duplicate to (true to false),
            DecryptResult.UnsupportedEnvelope to (true to false),
        )
        table.forEach { (result, expected) ->
            val (keep, defer) = expected
            assertEquals(result.toString(), keep, DecryptHistoryPolicy.shouldKeepWire(result))
            assertEquals(result.toString(), defer, DecryptHistoryPolicy.shouldDeferSessionRepair(result))
        }
    }

    @Test
    fun deferIsTrueOnlyForTheTwoRepairableResults() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        listOf(
            DecryptResult.Success("x"),
            DecryptResult.NotForThisDevice,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
        ).forEach { result ->
            assertFalse(result.toString(), DecryptHistoryPolicy.shouldDeferSessionRepair(result))
        }
    }

    @Test
    fun repairAttemptTrimsUnicodeWhitespaceAroundSenderId() {
        // Kotlin trim() 按 Unicode 空白类别裁边：全角空格与不间断空格也会被裁掉
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("\u3000u2\u3000", emptySet()))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("\u00a0u2\u00a0", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\u3000", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\u00a0", emptySet()))
    }

    @Test
    fun repairAttemptKeepsInteriorWhitespace() {
        // trim 只裁两端：中间的换行留着，id 按原样比对
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u\n2", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2 ", setOf("u2")))
    }

    @Test
    fun successNeverDefersRegardlessOfPayload() {
        // defer 只看结果类型，正文写什么都不影响
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Success("")))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Success("NoSession")))
    }
}
