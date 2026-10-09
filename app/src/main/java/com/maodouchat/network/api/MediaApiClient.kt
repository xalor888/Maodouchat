package com.maodouchat.network.api

// MediaApi 的薄复合门面：实现按主题拆到三个簇客户端，这里只做 by 委托转发。
internal object MediaApiClient : MediaApi,
    MediaUploadApi by MediaUploadApiClient,
    MediaDownloadApi by MediaDownloadApiClient,
    MediaImageApi by MediaImageApiClient

