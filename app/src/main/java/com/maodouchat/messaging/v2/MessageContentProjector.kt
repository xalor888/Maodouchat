package com.maodouchat.messaging.v2

import com.maodouchat.core.model.DecodedContentPayload
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.domain.messaging.MessageContentProjection
import com.maodouchat.util.MediaCache

/** M02：DATA 内容投影——委托给 :domain:messaging 的纯实现，附件解码与 URI 仍走 app 侧 MediaCache。 */
internal object MessageContentProjector {

    fun projectContent(payload: DecodedContentPayload): String =
        MessageContentProjection.projectContent(
            payload,
            MediaCache::decodeEncryptedAttachmentReference,
            MediaCache::attachmentUri,
        )

    fun projectMetadata(payload: DecodedContentPayload): MessageMeta =
        MessageContentProjection.projectMetadata(
            payload,
            MediaCache::decodeEncryptedAttachmentReference,
        )
}
