package com.maodouchat.ui.screen.explore

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.maodouchat.explore.repository.AndroidFeedRepository
import com.maodouchat.explore.repository.FeedController

class ExploreViewModel @JvmOverloads constructor(
    application: Application,
    private val feedController: FeedController = FeedController(AndroidFeedRepository(application)),
    private val orchestrator: ExploreOrchestrator = ExploreOrchestrator(
        application = application,
        scope = com.maodouchat.MaodouchatApp.instance.applicationScope,
        feedController = feedController
    )
) : AndroidViewModel(application) {

    val uiState: StateFlow<ExploreUiState> = orchestrator.uiState
    val entryNavigation: SharedFlow<String> = orchestrator.entryNavigation
    val visibilityOptions: List<VisibilityOption> = orchestrator.visibilityOptions

    fun refresh() = orchestrator.refresh()
    fun loadMore() = orchestrator.loadMore()

    fun openPostDetail(postId: String) = orchestrator.openPostDetail(postId)
    fun loadPostDetail(postId: String) = orchestrator.loadPostDetail(postId)
    fun openLikers(postId: String) = orchestrator.openLikers(postId)
    fun closeLikers() = orchestrator.closeLikers()

    fun toggleLike(post: PostDto) = orchestrator.toggleLike(post)

    fun openComments(postId: String) = orchestrator.openComments(postId)
    fun closeComments() = orchestrator.closeComments()
    fun loadOlderComments() = orchestrator.loadOlderComments()
    fun onCommentTextChange(text: String) = orchestrator.onCommentTextChange(text)
    fun setReplyToComment(comment: PostCommentDto) = orchestrator.setReplyToComment(comment)
    fun clearReplyToComment() = orchestrator.clearReplyToComment()
    fun sendComment() = orchestrator.sendComment()
    fun saveCommentEdit(comment: PostCommentDto, newText: String) = orchestrator.saveCommentEdit(comment, newText)
    fun deleteComment(comment: PostCommentDto) = orchestrator.deleteComment(comment)
    fun toggleCommentLike(comment: PostCommentDto) = orchestrator.toggleCommentLike(comment)
    fun reportComment(comment: PostCommentDto) = orchestrator.reportComment(comment)
    fun copyComment(comment: PostCommentDto) = orchestrator.copyComment(comment)

    fun onComposerTextChange(text: String) = orchestrator.onComposerTextChange(text)
    fun dismissComposerDraftHint() = orchestrator.dismissComposerDraftHint()
    fun clearComposer() = orchestrator.clearComposer()
    fun onVisibilitySelected(visibility: String) = orchestrator.onVisibilitySelected(visibility)
    fun addImages(uris: List<Uri>) = orchestrator.addImages(uris)
    fun retryDraftImage(draftId: String) = orchestrator.retryDraftImage(draftId)
    fun removeImage(id: String) = orchestrator.removeImage(id)
    fun resetPostState() = orchestrator.resetPostState()

    fun publishPost() = orchestrator.publishPost()
    fun requestEditPost(postId: String) = orchestrator.requestEditPost(postId)
    fun onEditPostTextChange(text: String) = orchestrator.onEditPostTextChange(text)
    fun onEditPostVisibilitySelected(visibility: String) = orchestrator.onEditPostVisibilitySelected(visibility)
    fun cancelEditPost() = orchestrator.cancelEditPost()
    fun confirmEditPost() = orchestrator.confirmEditPost()

    fun requestDeletePost(postId: String) = orchestrator.requestDeletePost(postId)
    fun cancelDeletePost() = orchestrator.cancelDeletePost()
    fun confirmDeletePost() = orchestrator.confirmDeletePost()

    fun reportPost(post: PostDto) = orchestrator.reportPost(post)
    fun blockPostAuthor(userId: String) = orchestrator.blockPostAuthor(userId)

    fun onEntryClick(entryId: String) = orchestrator.onEntryClick(entryId)
    fun showEntryPrompt(title: String) = orchestrator.showEntryPrompt(title)
    fun consumeMessage() = orchestrator.consumeMessage()

    override fun onCleared() {
        orchestrator.onCleared()
        super.onCleared()
    }
}
