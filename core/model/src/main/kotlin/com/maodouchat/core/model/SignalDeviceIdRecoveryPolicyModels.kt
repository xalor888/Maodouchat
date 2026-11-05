package com.maodouchat.core.model

/** The key package was accepted, but this device cannot receive fan-out until approval. */
class DevicePendingApprovalException(val deviceId: Int) :
    Exception("signal_device_pending_approval:$deviceId")

/** The server accepted the package but its authoritative device status could not be read. */
class DeviceStatusUnavailableException(cause: Throwable? = null) :
    Exception("signal_device_status_unavailable", cause)

/** Marks a failed migration so an ambiguous follow-up error cannot re-enable crypto. */
class IdentityRecoveryFailedException(cause: Throwable?) :
    Exception("signal_identity_recovery_failed", cause)
