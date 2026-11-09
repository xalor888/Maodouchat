package com.maodouchat.domain.messaging

/** How a claimed outbox row is dispatched in the flush loop. */
sealed interface OutboxClaimAction {
    /** Row is being prepared: encrypt and store the envelopes. */
    data object Prepare : OutboxClaimAction

    /** Row is prepared: send the stored envelopes. */
    data object Send : OutboxClaimAction

    /** Row is in a state the flush loop never claims: fail loudly. */
    data class Invalid(val state: String) : OutboxClaimAction
}

/**
 * Dispatches a claimed outbox row by its state. The state strings mirror
 * `MessagingV2OutboxState` (PREPARING / SENDING); anything else is invalid
 * because the claim query only ever returns those two states.
 */
fun decideOutboxClaimAction(state: String): OutboxClaimAction = when (state) {
    "PREPARING" -> OutboxClaimAction.Prepare
    "SENDING" -> OutboxClaimAction.Send
    else -> OutboxClaimAction.Invalid(state)
}
