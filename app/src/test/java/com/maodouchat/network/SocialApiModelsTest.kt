package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createPostRequestDefaultsUseAccountVisibility() {
        val request = CreatePostRequest(content = "hello")
        assertTrue(request.useDefaultVisibility)
        assertNull(request.visibility)
        val decoded = json.decodeFromString<CreatePostRequest>("""{"content":"hello"}""")
        assertTrue(decoded.useDefaultVisibility)
        assertEquals("hello", decoded.content)
    }

    @Test
    fun postDtoFillsLikeDefaultsWhenFeedOmitsThem() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p1","author":{"id":"u1","name":"Alice"},"content":"hi","createdAt":1}"""
        )
        assertEquals("PUBLIC", decoded.visibility)
        assertEquals(0, decoded.likeCount)
        assertEquals(0, decoded.commentCount)
        assertFalse(decoded.likedByMe)
        assertFalse(decoded.isMine)
        assertEquals(emptyList<String>(), decoded.imageUrls)
    }

    @Test
    fun postDtoRoundTripsLikedState() {
        val post = PostDto(
            id = "p2",
            author = UserDto(id = "u2", name = "Bob"),
            content = "public",
            visibility = "CONTACTS",
            createdAt = 9L,
            likeCount = 2,
            commentCount = 1,
            likedByMe = true,
            isMine = false
        )
        assertEquals(post, json.decodeFromString<PostDto>(json.encodeToString(post)))
    }

    @Test
    fun postCommentDefaultsNoParentAndNoLikes() {
        val decoded = json.decodeFromString<PostCommentDto>(
            """{"id":"c1","postId":"p1","author":{"id":"u1","name":"A"},"content":"nice","createdAt":3}"""
        )
        assertNull(decoded.parentId)
        assertEquals(0, decoded.likeCount)
        assertFalse(decoded.likedByMe)
        assertFalse(decoded.isMine)
    }

    @Test
    fun postLikersDefaultsEmptyList() {
        val decoded = json.decodeFromString<PostLikersResponse>("""{"postId":"p1"}""")
        assertEquals(emptyList<UserDto>(), decoded.likers)
        val filled = json.decodeFromString<PostLikersResponse>(
            """{"postId":"p1","likers":[{"id":"u2","name":"B"}]}"""
        )
        assertEquals(1, filled.likers.size)
        assertEquals("B", filled.likers[0].name)
    }

    @Test
    fun createCommentRequestReplyToIdRoundTrips() {
        val request = CreateCommentRequest(content = "re", replyToId = "c9")
        assertEquals(request, json.decodeFromString<CreateCommentRequest>(json.encodeToString(request)))
        val topLevel = json.decodeFromString<CreateCommentRequest>("""{"content":"top"}""")
        assertNull(topLevel.replyToId)
    }

    @Test
    fun nearbyLocationStatusDefaultsNotExpired() {
        val decoded = json.decodeFromString<NearbyLocationStatusResponse>("""{"sharing":true}""")
        assertTrue(decoded.sharing)
        assertEquals(0L, decoded.expiresAt)
    }

    @Test
    fun createPostRequestExplicitVisibilityDisablesDefault() {
        val request = CreatePostRequest(content = "x", visibility = "CONTACTS", useDefaultVisibility = false)
        val decoded = json.decodeFromString<CreatePostRequest>(json.encodeToString(request))
        assertEquals("CONTACTS", decoded.visibility)
        assertFalse(decoded.useDefaultVisibility)
        assertEquals(listOf("a.jpg"), json.decodeFromString<CreatePostRequest>(
            """{"content":"y","imageUrls":["a.jpg"]}"""
        ).imageUrls)
    }

    @Test
    fun postDtoEditedAtDefaultsNullAndEmptyImages() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p3","author":{"id":"u1","name":"A"},"content":"hi","createdAt":2}"""
        )
        assertNull(decoded.editedAt)
        assertEquals(emptyList<String>(), decoded.imageUrls)
    }

    @Test
    fun postCommentDtoReplyParentIdRoundTrips() {
        val comment = PostCommentDto(
            id = "c2",
            postId = "p1",
            author = UserDto(id = "u1", name = "A"),
            content = "reply",
            createdAt = 4L,
            parentId = "c1",
            likeCount = 1,
            likedByMe = true
        )
        assertEquals(comment, json.decodeFromString<PostCommentDto>(json.encodeToString(comment)))
    }

    @Test
    fun postLikersResponseRoundTripsMultipleLikers() {
        val response = PostLikersResponse(
            postId = "p1",
            likers = listOf(UserDto(id = "u2", name = "B"), UserDto(id = "u3", name = "C"))
        )
        val decoded = json.decodeFromString<PostLikersResponse>(json.encodeToString(response))
        assertEquals(2, decoded.likers.size)
        assertEquals("C", decoded.likers[1].name)
    }

    @Test
    fun createPostRequestEmptyContentRoundTrips() {
        val decoded = json.decodeFromString<CreatePostRequest>("{}")
        assertEquals("", decoded.content)
        assertTrue(decoded.useDefaultVisibility)
        assertNull(decoded.visibility)
    }

    @Test
    fun postDtoIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p4","author":{"id":"u1","name":"A"},"content":"hi","createdAt":2,"views":9}"""
        )
        assertEquals("p4", decoded.id)
        assertEquals(0, decoded.likeCount)
    }

    @Test
    fun nearbyUserResponseRoundTrips() {
        val response = NearbyUserResponse(
            user = UserDto(id = "u1", name = "A"),
            distanceMeters = 150,
            locationUpdatedAt = 42L
        )
        assertEquals(response, json.decodeFromString<NearbyUserResponse>(json.encodeToString(response)))
    }

    @Test
    fun commentLikeResponseDefaultsAndExplicitDecode() {
        val bare = json.decodeFromString<CommentLikeResponse>("{}")
        assertEquals("", bare.status)
        assertEquals(0, bare.likeCount)
        val liked = json.decodeFromString<CommentLikeResponse>("""{"status":"liked","likeCount":3}""")
        assertEquals("liked", liked.status)
        assertEquals(3, liked.likeCount)
    }

    @Test
    fun uploadPostImageResponseRoundTrips() {
        val response = UploadPostImageResponse(status = "ok", imageUrl = "https://x.example/y.jpg")
        assertEquals(response, json.decodeFromString<UploadPostImageResponse>(json.encodeToString(response)))
    }

    @Test
    fun editPostRequestDefaultsAndRoundTrip() {
        val bare = json.decodeFromString<EditPostRequest>("{}")
        assertEquals("", bare.content)
        assertNull(bare.visibility)
        val edited = EditPostRequest(content = "new", visibility = "CONTACTS")
        assertEquals(edited, json.decodeFromString<EditPostRequest>(json.encodeToString(edited)))
    }

    @Test
    fun postDtoEditedAtWithImagesRoundTrips() {
        val post = PostDto(
            id = "p9",
            author = UserDto(id = "u1", name = "A"),
            content = "hi",
            imageUrls = listOf("a.jpg", "b.jpg"),
            createdAt = 2L,
            editedAt = 99L,
            likeCount = 1
        )
        assertEquals(post, json.decodeFromString<PostDto>(json.encodeToString(post)))
    }

    @Test
    fun postDtoNegativeCountsDecodeAsIs() {
        val decoded = json.decodeFromString<PostDto>(
            """{"id":"p5","author":{"id":"u1","name":"A"},"content":"hi","createdAt":2,"likeCount":-1,"commentCount":-2}"""
        )
        assertEquals(-1, decoded.likeCount)
        assertEquals(-2, decoded.commentCount)
    }

    @Test
    fun postCommentDtoExplicitNullParentWithIsMine() {
        val decoded = json.decodeFromString<PostCommentDto>(
            """{"id":"c3","postId":"p1","author":{"id":"u1","name":"A"},"content":"hi","createdAt":5,"parentId":null,"isMine":true}"""
        )
        assertNull(decoded.parentId)
        assertTrue(decoded.isMine)
    }

    @Test
    fun nearbyLocationStatusNotSharingWithNegativeExpiry() {
        val decoded = json.decodeFromString<NearbyLocationStatusResponse>("""{"sharing":false,"expiresAt":-1}""")
        assertFalse(decoded.sharing)
        assertEquals(-1L, decoded.expiresAt)
    }

    @Test
    fun nearbyUserResponseZeroDistanceAndNegativeTimestampRoundTrips() {
        val response = NearbyUserResponse(
            user = UserDto(id = "u1", name = "A"),
            distanceMeters = 0,
            locationUpdatedAt = -5L
        )
        assertEquals(response, json.decodeFromString<NearbyUserResponse>(json.encodeToString(response)))
    }

    @Test
    fun editPostRequestExplicitNullVisibilityDecodes() {
        val decoded = json.decodeFromString<EditPostRequest>("""{"content":"hi","visibility":null}""")
        assertEquals("hi", decoded.content)
        assertNull(decoded.visibility)
    }

    @Test
    fun postCommentDtoFullDecode() {
        val decoded = json.decodeFromString<PostCommentDto>(
            """{"id":"c9","postId":"p1","author":{"id":"u1","name":"A"},"content":"hi","createdAt":7,"parentId":"c8","likeCount":4,"likedByMe":true,"isMine":true}"""
        )
        assertEquals("c8", decoded.parentId)
        assertEquals(4, decoded.likeCount)
        assertTrue(decoded.likedByMe)
        assertTrue(decoded.isMine)
    }

    @Test
    fun createPostRequestFullRoundTrips() {
        val request = CreatePostRequest(
            content = "hi",
            imageUrls = listOf("a.jpg", "b.jpg"),
            visibility = "PRIVATE",
            useDefaultVisibility = false
        )
        assertEquals(request, json.decodeFromString<CreatePostRequest>(json.encodeToString(request)))
    }

    @Test
    fun nearbyLocationStatusMaxExpiryDecodes() {
        val decoded = json.decodeFromString<NearbyLocationStatusResponse>(
            """{"sharing":true,"expiresAt":9223372036854775807}"""
        )
        assertTrue(decoded.sharing)
        assertEquals(Long.MAX_VALUE, decoded.expiresAt)
    }

    @Test
    fun commentLikeResponseNegativeCountDecodes() {
        val decoded = json.decodeFromString<CommentLikeResponse>("""{"status":"unliked","likeCount":-1}""")
        assertEquals("unliked", decoded.status)
        assertEquals(-1, decoded.likeCount)
    }

    @Test
    fun uploadPostImageResponseUnicodeUrlRoundTrips() {
        val response = UploadPostImageResponse(status = "ok", imageUrl = "https://x.example/图片.jpg")
        assertEquals(response, json.decodeFromString<UploadPostImageResponse>(json.encodeToString(response)))
    }
}
