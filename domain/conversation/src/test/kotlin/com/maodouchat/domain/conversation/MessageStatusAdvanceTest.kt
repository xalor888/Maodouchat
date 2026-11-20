package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// MessageStatus.canAdvanceTo 随成员函数迁入 conversation 的测试；
// deliveryRank 与 fromWire 的测试留守 core 的 MessageStatusMachineTest。
class MessageStatusAdvanceTest {
@Test
    fun `delivery ladder only advances forward`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.SENT))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.READ.canAdvanceTo(MessageStatus.SENT))
        assertFalse(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.SENT.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.SENT))
    }

@Test
    fun `canAdvanceTo same status and FAILED recovery edges`() {
        assertTrue(MessageStatus.READ.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.READ.canAdvanceTo(MessageStatus.FAILED))
    }

@Test
    fun `canAdvanceTo allows ladder skips but not backward steps`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.READ))
        assertFalse(MessageStatus.SENT.canAdvanceTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.DELIVERED))
    }

@Test
    fun `DELIVERED cannot advance to FAILED but FAILED can advance to DELIVERED`() {
        assertFalse(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.DELIVERED))
    }

@Test
    fun `canAdvanceTo rejects backward steps from every rung`() {
        assertFalse(MessageStatus.READ.canAdvanceTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.SENT))
        assertTrue(MessageStatus.READ.canAdvanceTo(MessageStatus.READ))
    }

@Test
    fun `canAdvanceTo same status holds for SENDING and SENT`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.SENT))
    }

@Test
    fun `canAdvanceTo skips rungs forward and holds DELIVERED and FAILED same-status`() {
        assertTrue(MessageStatus.SENDING.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.SENT.canAdvanceTo(MessageStatus.READ))
        assertTrue(MessageStatus.DELIVERED.canAdvanceTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.FAILED.canAdvanceTo(MessageStatus.FAILED))
    }
}
