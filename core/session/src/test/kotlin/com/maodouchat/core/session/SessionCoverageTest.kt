package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoverageTest {

    @Test
    fun coverageHoldsFields() {
        val coverage = SessionCoverage(
            candidateDeviceIds = listOf(1, 2),
            establishedDeviceIds = listOf(1),
            failuresByDevice = mapOf(2 to RuntimeException("no prekey"))
        )
        assertEquals(listOf(1, 2), coverage.candidateDeviceIds)
        assertEquals(listOf(1), coverage.establishedDeviceIds)
        assertEquals(1, coverage.failuresByDevice.size)
    }

    @Test
    fun emptyCoverageIsConsistent() {
        val coverage = SessionCoverage(
            candidateDeviceIds = emptyList(),
            establishedDeviceIds = emptyList(),
            failuresByDevice = emptyMap()
        )
        assertTrue(coverage.candidateDeviceIds.isEmpty())
        assertTrue(coverage.establishedDeviceIds.isEmpty())
        assertTrue(coverage.failuresByDevice.isEmpty())
    }

    @Test
    fun establishedIsSubsetOfCandidates() {
        val coverage = SessionCoverage(
            candidateDeviceIds = listOf(1, 2, 3),
            establishedDeviceIds = listOf(1, 3),
            failuresByDevice = mapOf(2 to RuntimeException("offline"))
        )
        assertTrue(coverage.establishedDeviceIds.all { it in coverage.candidateDeviceIds })
        assertEquals(setOf(2), coverage.failuresByDevice.keys)
    }
}
