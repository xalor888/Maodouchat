package com.maodouchat.server.watermark

// 原 ReferenceBlindWatermark 成员按域拆出的扩展函数（同包调用，零行为改动）。

/** 提取 [payloadBitCount] 位载荷；无水印/图像过小返回 null。 */
fun ReferenceBlindWatermark.extractPayload(
    pixels: IntArray,
    width: Int,
    height: Int,
    payloadBitCount: Int,
    passwordWm: Long = 1L,
    passwordImg: Long = 1L
): ByteArray? {
    require(pixels.size == width * height) { "pixels size mismatch" }
    if (payloadBitCount <= 0 || payloadBitCount % 8 != 0) return null
    val img = ArgbImage(pixels, width, height)
    val core = WatermarkCore(passwordImg)
    core.readImage(img)
    if (core.blockNum <= payloadBitCount) return null
    val raw = core.extractRaw()                    // 3×blockNum 软比特
    val wmAvg = DoubleArray(payloadBitCount)
    for (i in 0 until payloadBitCount) {
        var sum = 0.0
        var n = 0
        for (ch in 0 until 3) {
            var k = i
            while (k < core.blockNum) {
                sum += raw[ch][k]
                n++
                k += payloadBitCount
            }
        }
        wmAvg[i] = if (n == 0) 0.0 else sum / n
    }
    val (bits, centers) = oneDimKmeans(wmAvg)
    // 9.4xx：置信度门——真实水印的软比特在平均后紧密聚在 0/1 两侧（类中心分离大）；
    // 无水印图片的软比特近似均匀噪声，kmeans 硬分后类中心分离小。
    // 分离度过低判定无水印返回 null，避免对干净图片提取出全 1/全 0 假阳性。
    if (centers.size < 2 || centers[1] - centers[0] < 0.6) return null
    val unshuffled = BooleanArray(payloadBitCount)
    val idx = indexShuffle(payloadBitCount, passwordWm)
    for (k in 0 until payloadBitCount) {
        unshuffled[idx[k]] = bits[k]
    }
    return bitsToBytes(unshuffled)
}
