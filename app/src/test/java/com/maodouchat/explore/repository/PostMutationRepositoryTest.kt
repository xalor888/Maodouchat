package com.maodouchat.explore.repository

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PostMutationRepositoryTest {

    private lateinit var fakeSocialApi: FakeSocialApi
    private lateinit var mutationRepo: DefaultPostMutationRepository

    private val samplePost = PostDto(
        id = "post-100",
        author = UserDto(id = "author-1", name = "Alice"),
        content = "Hello world",
        likeCount = 5,
        likedByMe = false,
        createdAt = 1000L
    )

    private val sampleComment = PostCommentDto(
        id = "comment-200",
        postId = "post-100",
        author = UserDto(id = "author-2", name = "Bob"),
        content = "Nice post!",
        likeCount = 2,
        likedByMe = false,
        createdAt = 1010L
    )

    @Before
    fun setUp() {
        fakeSocialApi = FakeSocialApi()
        mutationRepo = DefaultPostMutationRepository(
            socialApi = fakeSocialApi,
            sessionGateCheck = { _, _ -> true }
        )
    }

    @Test
    fun togglePostLike_optimisticUpdate_successCommitsJournal() = runTest {
        val updates = mutableListOf<PostDto>()
        fakeSocialApi.likePostResult = Result.success(samplePost.copy(likeCount = 6, likedByMe = true))

        val result = mutationRepo.togglePostLike(
            ownerUserId = "me",
            token = "valid-token",
            post = samplePost,
            onOptimisticUpdate = { updates.add(it) }
        )

        assertTrue(result.isSuccess)
        assertEquals(1, updates.size)
        assertTrue(updates[0].likedByMe)
        assertEquals(6, updates[0].likeCount)
        // Journal entry committed and removed
        assertTrue(mutationRepo.journal.getPendingEntries().isEmpty())
    }

    @Test
    fun togglePostLike_networkFailure_rollsBackOptimistically() = runTest {
        val updates = mutableListOf<PostDto>()
        fakeSocialApi.likePostResult = Result.failure(IOException("Network disconnected"))

        val result = mutationRepo.togglePostLike(
            ownerUserId = "me",
            token = "valid-token",
            post = samplePost,
            onOptimisticUpdate = { updates.add(it) }
        )

        assertTrue(result.isFailure)
        // Should have 2 updates: 1st optimistic (liked=true, count=6), 2nd rollback (liked=false, count=5)
        assertEquals(2, updates.size)
        assertTrue(updates[0].likedByMe)
        assertEquals(6, updates[0].likeCount)

        assertFalse(updates[1].likedByMe)
        assertEquals(5, updates[1].likeCount)

        // Journal should be clean after rollback
        assertTrue(mutationRepo.journal.getPendingEntries().isEmpty())
    }

    @Test
    fun toggleCommentLike_optimisticAndRollback() = runTest {
        val updates = mutableListOf<PostCommentDto>()
        fakeSocialApi.likeCommentResult = Result.failure(IOException("Server error"))

        val result = mutationRepo.toggleCommentLike(
            ownerUserId = "me",
            token = "valid-token",
            postId = "post-100",
            comment = sampleComment,
            onOptimisticUpdate = { updates.add(it) }
        )

        assertTrue(result.isFailure)
        assertEquals(2, updates.size)
        // Optimistic
        assertTrue(updates[0].likedByMe)
        assertEquals(3, updates[0].likeCount)
        // Rolled back
        assertFalse(updates[1].likedByMe)
        assertEquals(2, updates[1].likeCount)
    }

    @Test
    fun toggleCommentLike_success_commitsAndReturnsUpdated() = runTest {
        fakeSocialApi.likeCommentResult = Result.success(CommentLikeResponse(status = "LIKED", likeCount = 3))

        val result = mutationRepo.toggleCommentLike(
            ownerUserId = "me",
            token = "valid-token",
            postId = "post-100",
            comment = sampleComment
        )

        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertTrue(updated.likedByMe)
        assertEquals(3, updated.likeCount)
        assertTrue(mutationRepo.journal.getPendingEntries().isEmpty())
    }

    @Test
    fun sessionGateRejected_failsImmediately() = runTest {
        val gatedRepo = DefaultPostMutationRepository(
            socialApi = fakeSocialApi,
            sessionGateCheck = { _, _ -> false }
        )

        val result = gatedRepo.togglePostLike(
            ownerUserId = "stale-user",
            token = "invalid",
            post = samplePost
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MutationSessionChangedException)
        assertTrue(fakeSocialApi.likePostCalls.isEmpty())
    }

    @Test
    fun deletePost_recordsSnapshotAndCommitsOnSuccess() = runTest {
        fakeSocialApi.deletePostResult = Result.success(Unit)

        val result = mutationRepo.deletePost(
            ownerUserId = "me",
            token = "valid-token",
            post = samplePost,
            originalIndex = 3
        )

        assertTrue(result.isSuccess)
        assertTrue(mutationRepo.journal.getPendingEntries().isEmpty())
        assertEquals(listOf("post-100"), fakeSocialApi.deletePostCalls)
    }

    private inner class FakeSocialApi : SocialApi {
        var likePostResult: Result<PostDto> = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        var unlikePostResult: Result<PostDto> = Result.success(PostDto("1", UserDto("1", "A"), "", createdAt = 0))
        var likeCommentResult: Result<CommentLikeResponse> = Result.success(CommentLikeResponse(status = "LIKED", likeCount = 1))
        var unlikeCommentResult: Result<CommentLikeResponse> = Result.success(CommentLikeResponse(status = "UNLIKED", likeCount = 0))
        var deletePostResult: Result<Unit> = Result.success(Unit)
        val likePostCalls = mutableListOf<String>()
        val deletePostCalls = mutableListOf<String>()

        override suspend fun likePost(token: String, postId: String): Result<PostDto> {
            likePostCalls.add(postId)
            return likePostResult
        }
        override suspend fun unlikePost(token: String, postId: String): Result<PostDto> {
            likePostCalls.add(postId)
            return unlikePostResult
        }
        override suspend fun likeComment(token: String, postId: String, commentId: String) = likeCommentResult
        override suspend fun unlikeComment(token: String, postId: String, commentId: String) = unlikeCommentResult
        override suspend fun deletePost(token: String, postId: String): Result<Unit> {
            deletePostCalls.add(postId)
            return deletePostResult
        }

        override suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String?) =
            Result.success(sampleComment)
        override suspend fun editPostComment(token: String, postId: String, commentId: String, content: String) =
            Result.success(sampleComment)
        override suspend fun deleteComment(token: String, postId: String, commentId: String) = Result.success(Unit)

        override suspend fun createReport(
            token: String,
            targetType: String,
            targetId: String,
            chatId: String?,
            messageId: String?,
            reason: String,
            description: String?
        ): Result<ReportResponse> =
            Result.success(
                ReportResponse(
                    id = "rep-1",
                    reporterId = "me",
                    targetType = targetType,
                    targetId = targetId,
                    reason = reason,
                    description = description,
                    status = "PENDING",
                    createdAt = 0L
                )
            )

        override suspend fun getUsers(token: String, limit: Int, offset: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getAllSearchableUsers(token: String, pageSize: Int, maxUsers: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getUser(token: String, userId: String) = Result.success(UserDto("1", "A"))
        override suspend fun searchUsers(token: String, query: String, limit: Int) = Result.success(emptyList<UserDto>())
        override suspend fun getCurrentUser(token: String) = Result.success(UserDto("1", "A"))
        override suspend fun getCurrentUserPublic(token: String) = Result.failure<CurrentUserPublicResponse>(UnsupportedOperationException())
        override suspend fun getPublicProfile(username: String) = Result.failure<PublicProfileResponse>(UnsupportedOperationException())
        override suspend fun setUsername(token: String, username: String) = Result.failure<SetUsernameResponse>(UnsupportedOperationException())
        override suspend fun clearUsername(token: String) = Result.success(Unit)
        override suspend fun getNearbyLocationStatus(token: String) = Result.failure<NearbyLocationStatusResponse>(UnsupportedOperationException())
        override suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double) = Result.failure<NearbyLocationStatusResponse>(UnsupportedOperationException())
        override suspend fun stopNearbyLocationSharing(token: String) = Result.failure<NearbyLocationStatusResponse>(UnsupportedOperationException())
        override suspend fun getNearbyUsers(token: String, radiusKm: Double, limit: Int) = Result.success(emptyList<NearbyUserResponse>())
        override suspend fun blockUser(token: String, userId: String) = Result.success(Unit)
        override suspend fun unblockUser(token: String, userId: String) = Result.success(Unit)
        override suspend fun getBlockedUsers(token: String) = Result.success(emptyList<String>())
        override suspend fun getBlockedUserDetails(token: String) = Result.success(emptyList<UserDto>())
        override suspend fun getPosts(token: String, limit: Int, before: Long?, beforeId: String?, authorId: String?) = Result.success(emptyList<PostDto>())
        override suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String?) = Result.success(samplePost)
        override suspend fun getPost(token: String, postId: String) = Result.success(samplePost)
        override suspend fun editPost(token: String, postId: String, content: String, visibility: String?) = Result.success(samplePost)
        override suspend fun getPostComments(token: String, postId: String, limit: Int, before: Long?, beforeId: String?) = Result.success(emptyList<PostCommentDto>())
        override suspend fun getPostLikers(token: String, postId: String, limit: Int) = Result.success(PostLikersResponse(postId))
    }
}
