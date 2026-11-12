package com.maodouchat.chatlist

import com.maodouchat.core.model.requiresGroupOwnershipTransfer
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListDraftTest {
    @Test
    fun ownerTransferErrorUsesDedicatedRecoveryFlow() {
        assertTrue(
            requiresGroupOwnershipTransfer(
                ApiException(
                    kind = ApiFailureKind.HTTP,
                    statusCode = 409,
                    serverMessage = "localized message",
                    serverCode = "GROUP_OWNER_TRANSFER_REQUIRED"
                )
            )
        )
        assertFalse(
            requiresGroupOwnershipTransfer(
                ApiException(ApiFailureKind.HTTP, statusCode = 409, serverCode = "OTHER_CONFLICT")
            )
        )
    }

    @Test
    fun transferCheckIgnoresKindAndStatusButNeedsCode() {
        assertTrue(
            requiresGroupOwnershipTransfer(
                ApiException(ApiFailureKind.NETWORK, statusCode = 500, serverCode = "GROUP_OWNER_TRANSFER_REQUIRED")
            )
        )
        assertFalse(requiresGroupOwnershipTransfer(null))
        assertFalse(requiresGroupOwnershipTransfer(RuntimeException("boom")))
    }
}
