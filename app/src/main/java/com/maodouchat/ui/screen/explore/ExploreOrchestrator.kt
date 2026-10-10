package com.maodouchat.ui.screen.explore

import android.app.Application
import android.content.Context
import com.maodouchat.R
import com.maodouchat.explore.repository.DefaultMediaUploadQueue
import com.maodouchat.explore.repository.DefaultPostMutationRepository
import com.maodouchat.explore.repository.DraftRepository
import com.maodouchat.explore.repository.MediaUploadQueue
import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.explore.repository.SharedPrefsDraftRepository
import com.maodouchat.explore.usecase.CommentPostUseCase
import com.maodouchat.explore.usecase.LoadFeedUseCase
import com.maodouchat.explore.usecase.PublishPostUseCase
import com.maodouchat.explore.usecase.ResolveNearbyUseCase
import com.maodouchat.explore.usecase.ToggleLikeUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import com.maodouchat.explore.repository.AndroidFeedRepository
import com.maodouchat.explore.repository.FeedController

class ExploreOrchestrator(
    internal val application: Application,
    internal val scope: CoroutineScope,
    internal val feedController: FeedController = FeedController(AndroidFeedRepository(application)),
    private val postMutationRepository: PostMutationRepository = DefaultPostMutationRepository(),
    internal val draftRepository: DraftRepository = SharedPrefsDraftRepository(
        application.getSharedPreferences("explore_drafts", Context.MODE_PRIVATE)
    ),
    internal val uploadQueue: MediaUploadQueue = DefaultMediaUploadQueue(),
    internal val loadFeedUseCase: LoadFeedUseCase = LoadFeedUseCase(AndroidFeedRepository(application)),
    internal val publishPostUseCase: PublishPostUseCase = PublishPostUseCase(postMutationRepository, draftRepository, uploadQueue),
    internal val toggleLikeUseCase: ToggleLikeUseCase = ToggleLikeUseCase(postMutationRepository),
    private val commentPostUseCase: CommentPostUseCase = CommentPostUseCase(postMutationRepository),
    private val resolveNearbyUseCase: ResolveNearbyUseCase = ResolveNearbyUseCase()
) {

    internal val _uiState = MutableStateFlow(
        ExploreUiState(
            composerText = "",
            selectedVisibility = "PRIVATE"
        )
    )
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    internal val _entryNavigation = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val entryNavigation: SharedFlow<String> = _entryNavigation.asSharedFlow()

    internal val loadMoreMutex = Mutex()
    internal var feedGeneration = 0L
    internal var refreshJob: Job? = null
    internal var postDetailGeneration = 0L
    internal var postDetailJob: Job? = null
    internal var privacyDefaultsGeneration = 0L
    internal var privacyDefaultsJob: Job? = null
    internal val likeJobs = ConcurrentHashMap<String, Job>()
    internal val commentController = ExploreCommentController(
        application = application,
        scope = scope,
        commentPostUseCase = commentPostUseCase,
        toggleLikeUseCase = toggleLikeUseCase,
        state = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        textFn = { id, args -> text(id, *args) },
    )

    init {
        val ownerId = draftOwnerId()
        val draft = draftRepository.getDraft(ownerId, "PRIVATE")
        val restored = draft.composerText.isNotBlank()
        _uiState.update {
            it.copy(
                composerText = draft.composerText,
                selectedVisibility = draft.selectedVisibility,
                composerDraftRestored = restored
            )
        }
        loadPrivacyDefaults()
        refresh()
    }

    internal fun text(id: Int, vararg args: Any): String = application.getString(id, *args)

    val visibilityOptions = listOf(
        VisibilityOption("PUBLIC", text(R.string.explore_visibility_public), text(R.string.explore_visibility_public_subtitle)),
        VisibilityOption("CONTACTS", text(R.string.explore_visibility_contacts), text(R.string.explore_visibility_contacts_subtitle)),
        VisibilityOption("PRIVATE", text(R.string.explore_visibility_private), text(R.string.explore_visibility_private_subtitle))
    )

    internal fun isCurrentOwner(expectedUserId: String): Boolean =
        com.maodouchat.session.CurrentSession.snapshot().userId == expectedUserId && expectedUserId.isNotBlank()

    internal fun draftOwnerId(): String = com.maodouchat.session.CurrentSession.ownerUserId()

    fun onCleared() {
        val uploadedUrls = _uiState.value.readyImageUrls
        val ownerUserId = draftOwnerId()
        if (uploadedUrls.isNotEmpty() && com.maodouchat.session.CurrentSession.hasSession() && ownerUserId.isNotBlank()) {
            uploadedUrls.forEach { url ->
                uploadQueue.cancel(url)
            }
        }
        uploadQueue.clear()
    }
}