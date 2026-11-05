package com.maodouchat.core.model

/** M07：附件上传 + finalize 编排的处置结果（Worker 据此映射到 WorkManager Result）。 */
enum class AttachmentTransferDisposition { SUCCESS, RETRY, FAILURE }
