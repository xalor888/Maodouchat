package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchContentKindTest {

    private fun kind(
        queryBlank: Boolean = false,
        isIndexing: Boolean = false,
        isSearching: Boolean = false,
        resultsEmpty: Boolean = true,
        hasError: Boolean = false,
        mode: GlobalSearchMode = GlobalSearchMode.KEYWORD,
        aiSearchCompleted: Boolean = false
    ) = globalSearchContentKind(
        queryBlank, isIndexing, isSearching, resultsEmpty, hasError, mode, aiSearchCompleted
    )

    @Test
    fun `blank query wins over loading`() {
        assertEquals(GlobalSearchContentKind.RECENT_OR_INITIAL, kind(queryBlank = true, isIndexing = true))
    }

    @Test
    fun `indexing or searching is loading`() {
        assertEquals(GlobalSearchContentKind.LOADING, kind(isIndexing = true))
        assertEquals(GlobalSearchContentKind.LOADING, kind(isSearching = true))
    }

    @Test
    fun `empty results with error is error empty`() {
        assertEquals(GlobalSearchContentKind.ERROR_EMPTY, kind(hasError = true))
    }

    @Test
    fun `empty results without error is no results`() {
        assertEquals(GlobalSearchContentKind.NO_RESULTS, kind())
    }

    @Test
    fun `ai mode before completion is ai ready`() {
        assertEquals(
            GlobalSearchContentKind.AI_READY,
            kind(mode = GlobalSearchMode.AI, aiSearchCompleted = false)
        )
    }

    @Test
    fun `ai mode after completion falls back to no results`() {
        assertEquals(
            GlobalSearchContentKind.NO_RESULTS,
            kind(mode = GlobalSearchMode.AI, aiSearchCompleted = true)
        )
    }

    @Test
    fun `non-empty results wins`() {
        assertEquals(GlobalSearchContentKind.RESULTS, kind(resultsEmpty = false))
        assertEquals(GlobalSearchContentKind.RESULTS, kind(resultsEmpty = false, hasError = true))
    }
}
