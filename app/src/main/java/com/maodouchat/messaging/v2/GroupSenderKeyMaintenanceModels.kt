package com.maodouchat.messaging.v2

import com.maodouchat.network.SenderKeyDistributionStatusDto

sealed interface GroupSenderKeyMaintenanceOutcome {
    data object Skipped : GroupSenderKeyMaintenanceOutcome

    data class Ready(
        val status: SenderKeyDistributionStatusDto?,
        val localHasSenderKey: Boolean,
    ) : GroupSenderKeyMaintenanceOutcome

    data class Pending(
        val status: SenderKeyDistributionStatusDto?,
        val localHasSenderKey: Boolean,
        val error: Throwable,
        val retryQueueError: Throwable? = null,
    ) : GroupSenderKeyMaintenanceOutcome

    data class Failed(
        val error: Throwable,
        val retryQueueError: Throwable? = null,
    ) : GroupSenderKeyMaintenanceOutcome
}
