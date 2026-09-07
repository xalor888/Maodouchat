package com.maodouchat.server.messaging.v2

import com.maodouchat.server.repository.EncryptableDeviceDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationDeviceSnapshotStoreDirectoryTest {

    @Test
    fun `confirmed targets come only from EncryptableDeviceDirectory`() {
        val directory = object : EncryptableDeviceDirectory {
            override fun getConfirmedDeviceTargets(userIds: Collection<String>): Set<Pair<String, Int>> =
                setOf("alice" to 1, "bob" to 2)

            override fun getDeviceIds(userId: String, confirmedOnly: Boolean): List<Int> = emptyList()

            override fun isDeviceConfirmed(userId: String, deviceId: Int): Boolean = true
        }
        val store = ConversationDeviceSnapshotStore(directory)
        val targets = store.confirmedEncryptableDeviceTargets(listOf("alice", "bob", "carol"))
        assertEquals(
            setOf(DeviceTarget("alice", 1), DeviceTarget("bob", 2)),
            targets.toSet(),
        )
    }
}
