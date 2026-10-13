package com.maodouchat.contacts.usecase

/**
 * 好友申请用例契约
 */
interface FriendRequestUseCase {
    /**
     * 拉取好友申请列表（自动排重与按时间倒序排序）
     */
    suspend fun loadRequests(): Result<FriendRequestsSnapshot>

    /**
     * 发起好友申请（防并发重复提交）
     */
    suspend fun sendFriendRequest(targetUserId: String, note: String = ""): Result<FriendRequestItem>

    /**
     * 接受好友申请（具备接受/撤回竞态拦截）
     */
    suspend fun acceptFriendRequest(requestId: String): Result<FriendRequestItem?>

    /**
     * 拒绝好友申请
     */
    suspend fun rejectFriendRequest(requestId: String): Result<Unit>

    /**
     * 撤回好友申请（具备接受/撤回竞态拦截）
     */
    suspend fun cancelFriendRequest(requestId: String): Result<Unit>

    /**
     * 批量接受好友申请
     */
    suspend fun batchAcceptFriendRequests(requestIds: List<String>): Map<String, Result<FriendRequestItem?>>

    /**
     * 批量拒绝好友申请
     */
    suspend fun batchRejectFriendRequests(requestIds: List<String>): Map<String, Result<Unit>>
}
