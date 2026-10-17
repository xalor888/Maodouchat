package com.maodouchat.crypto

/** The key package was accepted, but this device cannot receive fan-out until approval. */
internal class DevicePendingApprovalException(val deviceId: Int) :
    Exception("signal_device_pending_approval:$deviceId")

/** The server accepted the package but its authoritative device status could not be read. */
internal class DeviceStatusUnavailableException(cause: Throwable? = null) :
    Exception("signal_device_status_unavailable", cause)

/** Marks a failed migration so an ambiguous follow-up error cannot re-enable crypto. */
internal class IdentityRecoveryFailedException(cause: Throwable?) :
    Exception("signal_identity_recovery_failed", cause)
