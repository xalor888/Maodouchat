package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFolderDragPreviewPolicyTest {

    @Test
    fun `no drag or zero pitch returns minus one`() {
        assertEquals(-1, ChatFolderDragPreviewPolicy.dragPreviewTarget(-1, 100f, 50f, 5))
        assertEquals(-1, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, 100f, 0f, 5))
        assertEquals(-1, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, 100f, -10f, 5))
    }

    @Test
    fun `zero offset keeps the dragged index`() {
        assertEquals(2, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, 0f, 50f, 5))
    }

    @Test
    fun `offset rounds to the nearest row`() {
        assertEquals(3, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, 26f, 50f, 5))
        assertEquals(2, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, 24f, 50f, 5))
        assertEquals(1, ChatFolderDragPreviewPolicy.dragPreviewTarget(2, -26f, 50f, 5))
    }

    @Test
    fun `target clamps to both ends`() {
        assertEquals(4, ChatFolderDragPreviewPolicy.dragPreviewTarget(3, 500f, 50f, 5))
        assertEquals(0, ChatFolderDragPreviewPolicy.dragPreviewTarget(1, -500f, 50f, 5))
    }
}
