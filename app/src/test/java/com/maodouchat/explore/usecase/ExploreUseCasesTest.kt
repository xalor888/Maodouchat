package com.maodouchat.explore.usecase

import com.maodouchat.explore.policy.PostVisibility
import com.maodouchat.explore.repository.DraftRepository
import com.maodouchat.explore.repository.ExploreDraft
import com.maodouchat.explore.repository.MediaUploadQueue
import com.maodouchat.explore.repository.PostMutationJournal
import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.explore.repository.UploadItem
import com.maodouchat.network.CommentLikeResponse
import com.maodouchat.network.CurrentUserPublicResponse
import com.maodouchat.network.NearbyLocationStatusResponse
import com.maodouchat.network.NearbyUserResponse
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import com.maodouchat.network.PostLikersResponse
import com.maodouchat.network.PublicProfileResponse
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.SetUsernameResponse
import com.maodouchat.network.UserDto
import com.maodouchat.network.api.SocialApi
import com.maodouchat.ui.screen.explore.ExploreFeedPolicy
import com.maodouchat.ui.screen.explore.FeedRepository
import com.maodouchat.ui.screen.explore.FeedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreUseCasesTest {

    private val sampleAuthor = UserDto(id = "user-1", name = "Alice")
    private val samplePost = PostDto(
        id = "post-1",
        author = sampleAuthor,
        content = "Test content",
        createdAt = 1000L
    )
    private val sampleComment = PostCommentDto(
        id = "comment-1",
        postId = "post-1",
        author = sampleAuthor,
        content = "Great post!",
        createdAt = 1010L
    )

    @Test
    fun loadFeedUseCase_refresh_delegatesToRepository() = runTest {
        val fakeFeedRepo = FakeFeedRepository()
        val useCase = LoadFeedUseCase(feedRepository = fakeFeedRepo)

        val result = useCase.refresh(ownerUserId = "user-1")
        assertTrue(result.isSuccess)
        assertEquals(listOf("load:user-1"), fakeFeedRepo.calls)
    }

    @Test
    fun publishPostUseCase_emptyContentAndNoImages_failsValidation() = runTest {
        val fakeMutationRepo = FakePostMutationRepository()
        val fakeDraftRepo = FakeDraftRepository()
        val fakeUploadQueue = FakeMediaUploadQueue()
        val useCase = PublishPostUseCase(fakeMutationRepo, fakeDraftRepo, fakeUploadQueue)

        val result = useCase.publish(
            ownerUserId = "user-1",
            token = "token-1",
            content = "   ",
            imageUrls = emptyList()
        )

        assertTrue(result.isFailure)
        assertFalse(fakeDraftRepo.clearDraftCalled)
    }

    @Test
    fun publishPostUseCase_validContent_publishesAndClearsDraft() = runTest {
        val fakeMutationRepo = FakePostMutationRepository()
        val fakeDraftRepo = FakeDraftRepository()
        val fakeUploadQueue = FakeMediaUploadQueue()
        val useCase = PublishPostUseCase(fakeMutationRepo, fakeDraftRepo, fakeUploadQueue)

        val result = useCase.publish(
            ownerUserId = "user-1",
            token = "token-1",
            content = "Hello World!",
            imageUrls = listOf("https://example.com/img.jpg"),
            visibility = PostVisibility.CONTACTS
        )

        assertTrue(result.isSuccess)
        assertTrue(fakeDraftRepo.clearDraftCalled)
        assertEquals("CONTACTS", fakeMutationRepo.lastVisibility)
    }

    @Test
    fun toggleLikeUseCase_delegatesToMutationRepo() = runTest {
        val fakeMutationRepo = FakePostMutationRepository()
        val useCase = ToggleLikeUseCase(fakeMutationRepo)

        val postResult = useCase.togglePostLike("user-1", "token-1", samplePost)
        assertTrue(postResult.isSuccess)

        val commentResult = useCase.toggleCommentLike("user-1", "token-1", "post-1", sampleComment)
        assertTrue(commentResult.isSuccess)
    }

    @Test
    fun commentPostUseCase_sendAndReport() = runTest {
        val fakeMutationRepo = FakePostMutationRepository()
        val fakeSocialApi = FakeSocialApi()
        val useCase = CommentPostUseCase(fakeMutationRepo, fakeSocialApi)

        val commentResult = useCase.sendComment("user-1", "token-1", "post-1", "A comment")
        assertTrue(commentResult.isSuccess)

        val reportResult = useCase.reportComment("user-1", "token-1", "comment-1", "Spam")
        assertTrue(reportResult.isSuccess)
    }

    @Test
    fun resolveNearbyUseCase_updatesAndFetchesNearby() = runTest {
        val fakeSocialApi = FakeSocialApi()
        val useCase = ResolveNearbyUseCase(fakeSocialApi)

        val statusResult = useCase.updateNearbyLocation("token-1", 37.7749, -122.4194)
        assertTrue(statusResult.isSuccess)

        val usersResult = useCase.getNearbyUsers("token-1", 5.0, 20)
        assertTrue(usersResult.isSuccess)
    }

    // Fakes
    private class FakeFeedRepository : FeedRepository {
        val calls = mutableListOf<String>()
        override fun currentSession(): FeedSession? = FeedSession("user-1")
        override fun isCurrent(session: FeedSession): Boolean = true
        override suspend fun load(session: FeedSession, cursor: ExploreFeedPolicy.Cursor?): Result<List<PostDto>> {
            calls.add("load:${session.ownerUserId}")
            return Result.success(emptyList())
        }
        override suspend fun publish(session: FeedSession, content: String, imageUrls: List<String>, visibility: String?): Result<PostDto> =
            Result.success(PostDto("p1", UserDto("u1", "A"), content, createdAt = 0))
    }

    private class FakeDraftRepository : DraftRepository {
        var clearDraftCalled = false
        override fun getDraft(ownerUserId: String, defaultVisibility: String?): ExploreDraft = ExploreDraft()
        override fun saveComposerText(ownerUserId: String, text: String) {}
        override fun saveVisibility(ownerUserId: String, visibility: String) {}
        override fun saveImageUris(ownerUserId: String, uris: List<String>) {}
        override fun clearComposerDraft(ownerUserId: String) { clearDraftCalled = true }
        override fun clearAllDrafts(ownerUserId: String) { clearDraftCalled = true }
    }

    private class FakeMediaUploadQueue : MediaUploadQueue {
        override val itemsFlow: StateFlow<Map<String, UploadItem>> = MutableStateFlow(emptyMap())
        override fun enqueue(id: String, ownerUserId: String, base64Data: String, maxRetries: Int) {}
        override fun startUpload(id: String, token: String) = null
        override fun retry(id: String, token: String) = null
        override fun cancel(id: String) {}
        override suspend fun discardUploaded(token: String, ownerUserId: String, url: String) = Result.success(Unit)
        override fun clear() {}
    }

    private class FakePostMutationRepository : PostMutationRepository {
        var lastVisibility: String? = null
        override val journal: PostMutationJournal = com.maodouchat.explore.repository.InMemoryPostMutationJournal()

        override suspend fun togglePostLike(ownerUserId: String, token: String, post: PostDto, onOptimisticUpdate: (PostDto) -> Unit) =
            Result.success(post.copy(likedByMe = !post.likedByMe))

        override suspend fun toggleCommentLike(ownerUserId: String, token: String, postId: String, comment: PostCommentDto, onOptimisticUpdate: (PostCommentDto) -> Unit) =
            Result.success(comment.copy(likedByMe = !comment.likedByMe))

        override suspend fun publishPost(ownerUserId: String, token: String, content: String, imageUrls: List<String>, visibility: String?): Result<PostDto> {
            lastVisibility = visibility
            return Result.success(PostDto("p1", UserDto("u1", "A"), content, createdAt = 0))
        }

        override suspend fun editPost(ownerUserId: String, token: String, postId: String, content: String, visibility: String?) =
            Result.success(PostDto(postId, UserDto("u1", "A"), content, createdAt = 0))

        override suspend fun deletePost(ownerUserId: String, token: String, post: PostDto, originalIndex: Int) = Result.success(Unit)

        override suspend fun addComment(ownerUserId: String, token: String, postId: String, content: String, replyToCommentId: String?) =
            Result.success(PostCommentDto("c1", postId, UserDto("u1", "A"), content, createdAt = 0))

        override suspend fun editComment(ownerUserId: String, token: String, postId: String, commentId: String, newContent: String) =
            Result.success(PostCommentDto(commentId, postId, UserDto("u1", "A"), newContent, createdAt = 0))

        override suspend fun deleteComment(ownerUserId: String, token: String, postId: String, comment: PostCommentDto, originalIndex: Int) = Result.success(Unit)

        override suspend fun reportPost(ownerUserId: String, token: String, postId: String, reason: String, description: String?) =
            Result.success(ReportResponse("r1", "u1", "POST", postId, reason = reason, status = "PENDING", createdAt = 0L))

        override suspend fun reportComment(ownerUserId: String, token: String, commentId: String, reason: String, description: String?) =
            Result.success(ReportResponse("r1", "u1", "COMMENT", commentId, reason = reason, status = "PENDING", createdAt = 0L))
    }

    private class FakeSocialApi : SocialApi {
        override suspend fun getNearbyLocationStatus(token: String) = Result.success(NearbyLocationStatusResponse(true, 0L))
        override suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double) = Result.success(NearbyLocationStatusResponse(true, 0L))
        override suspend fun stopNearbyLocationSharing(token: String) = Result.success(NearbyLocationStatusResponse(false, 0L))
        override suspend fun getNearbyUsers(token: String, radiusKm: Double, limit: Int) = Result.success(emptyList<NearbyUserResponse>())
        override suspend fun getUsers(token: String, limit: Int, offset: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getAllSearchableUsers(token: String, pageSize: Int, maxUsers: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getUser(token: String, userId: String) = Result.success(UserDto("1", "A"))
        override suspend fun searchUsers(token: String, query: String, limit: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getCurrentUser(token: String) = Result.success(UserDto("1", "A"))
        override suspend fun getCurrentUserPublic(token: String) = Result.failure<CurrentUserPublicResponse>(UnsupportedOperationException())
        override suspend fun getPublicProfile(username: String) = Result.failure<PublicProfileResponse>(UnsupportedOperationException())
        override suspend fun setUsername(token: String, username: String) = Result.failure<SetUsernameResponse>(UnsupportedOperationException())
        override suspend fun clearUsername(token: String) = Result.success(Unit)
        override suspend fun blockUser(token: String, userId: String) = Result.success(Unit)
        override suspend fun unblockUser(token: String, userId: String) = Result.success(Unit)
        override suspend fun getBlockedUsers(token: String) = Result.success(emptyList<String>())
        override suspend fun getBlockedUserDetails(token: String) = Result.success(emptyList<UserDto>())
        override suspend fun getPosts(token: String, limit: Int, before: Long?, beforeId: String?, authorId: String?) = Result.success(emptyList<PostDto>())
        override suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String?) = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        override suspend fun getPost(token: String, postId: String) = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        override suspend fun editPost(token: String, postId: String, content: String, visibility: String?) = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        override suspend fun deletePost(token: String, postId: String) = Result.success(Unit)
        override suspend fun likePost(token: String, postId: String) = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        override suspend fun unlikePost(token: String, postId: String) = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        override suspend fun getPostComments(token: String, postId: String, limit: Int, before: Long?, beforeId: String?) = Result.success(emptyList<PostCommentDto>())
        override suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String?) = Result.success(PostCommentDto("1", postId, UserDto("1", "A"), "", createdAt = 0))
        override suspend fun editPostComment(token: String, postId: String, commentId: String, content: String) = Result.success(PostCommentDto(commentId, postId, UserDto("1", "A"), "", createdAt = 0))
        override suspend fun getPostLikers(token: String, postId: String, limit: Int) = Result.success(PostLikersResponse(postId))
        override suspend fun deleteComment(token: String, postId: String, commentId: String) = Result.success(Unit)
        override suspend fun likeComment(token: String, postId: String, commentId: String) = Result.success(CommentLikeResponse("LIKED", 1))
        override suspend fun unlikeComment(token: String, postId: String, commentId: String) = Result.success(CommentLikeResponse("UNLIKED", 0))
        override suspend fun createReport(token: String, targetType: String, targetId: String, chatId: String?, messageId: String?, reason: String, description: String?) =
            Result.success(ReportResponse("r1", "u1", targetType, targetId, reason = reason, status = "PENDING", createdAt = 0L))
    }
}
