package com.maodouchat.domain.groups

import com.maodouchat.core.model.SenderKeyDistributionTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SenderKeyTargetFilterPolicyTest {

    private fun target(userId: String, status: String) =
        SenderKeyDistributionTargetDto(
            userId = userId, deviceId = 1, status = status, updatedAt = 0L
        )

    private val targets = listOf(
        target("u1", "SENT"),
        target("u2", "failed"),
        target("u3", "Pending"),
        target("u4", "SENT"),
    )

    @Test
    fun `ALL returns targets unchanged`() {
        assertEquals(targets,
            SenderKeyTargetFilterPolicy.filter(targets, SenderKeyTargetFilter.ALL))
    }

    @Test
    fun `FAILED matches case-insensitively`() {
        assertEquals(listOf(targets[1]),
            SenderKeyTargetFilterPolicy.filter(targets, SenderKeyTargetFilter.FAILED))
    }

    @Test
    fun `PENDING matches case-insensitively`() {
        assertEquals(listOf(targets[2]),
            SenderKeyTargetFilterPolicy.filter(targets, SenderKeyTargetFilter.PENDING))
    }

    @Test
    fun `SENT keeps sent targets in order`() {
        assertEquals(listOf(targets[0], targets[3]),
            SenderKeyTargetFilterPolicy.filter(targets, SenderKeyTargetFilter.SENT))
    }

    @Test
    fun `empty targets stays empty`() {
        assertEquals(emptyList<SenderKeyDistributionTargetDto>(),
            SenderKeyTargetFilterPolicy.filter(emptyList(), SenderKeyTargetFilter.FAILED))
    }
}
