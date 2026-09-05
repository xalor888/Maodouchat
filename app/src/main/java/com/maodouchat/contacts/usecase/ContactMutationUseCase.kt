package com.maodouchat.contacts.usecase

/**
 * 联系人关系与属性变更用例契约
 */
interface ContactMutationUseCase {
    /**
     * 设置联系人本地备注名
     */
    suspend fun setNickname(userId: String, nickname: String): Result<Unit>

    /**
     * 删除好友
     */
    suspend fun removeFriend(userId: String): Result<Unit>

    /**
     * 拉黑用户
     */
    suspend fun blockUser(userId: String): Result<Unit>

    /**
     * 解除拉黑
     */
    suspend fun unblockUser(userId: String): Result<Unit>
}
