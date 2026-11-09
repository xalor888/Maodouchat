package com.maodouchat.attachment

import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.domain.messaging.AttachmentWorkerAction
import com.maodouchat.domain.messaging.AttachmentWorkerPolicy

/** 附件传输 worker 决策的实体侧薄委托；规则见 :domain:messaging 的 AttachmentWorkerPolicy。 */
fun AttachmentTransferEntity.nextWorkerAction(): AttachmentWorkerAction =
    AttachmentWorkerPolicy.nextAction(state, hasCompletedUpload())

fun AttachmentTransferEntity.shouldScheduleAfterProcessDeath(): Boolean =
    AttachmentWorkerPolicy.shouldScheduleAfterProcessDeath(state, hasCompletedUpload())

