package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MediaCenterCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaCenterCategoryKindTest {

    @Test
    fun `all five categories map to their kinds`() {
        assertEquals(MediaCenterCategoryKind.MEDIA, mediaCenterCategoryKind(MediaCenterCategory.MEDIA))
        assertEquals(MediaCenterCategoryKind.FILES, mediaCenterCategoryKind(MediaCenterCategory.FILES))
        assertEquals(MediaCenterCategoryKind.VOICE, mediaCenterCategoryKind(MediaCenterCategory.VOICE))
        assertEquals(MediaCenterCategoryKind.LINKS, mediaCenterCategoryKind(MediaCenterCategory.LINKS))
        assertEquals(MediaCenterCategoryKind.LOCATION, mediaCenterCategoryKind(MediaCenterCategory.LOCATION))
    }
}
