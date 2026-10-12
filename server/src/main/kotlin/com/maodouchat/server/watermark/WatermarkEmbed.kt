package com.maodouchat.server.watermark

// 原 ReferenceBlindWatermark 成员按域拆出的扩展函数（同包调用，零行为改动）。

/** 在 ARGB 像素上嵌入 [payload]（8bit/字节展开位流），返回新像素数组；原数组不变。 */
fun ReferenceBlindWatermark.embedPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    payload: ByteArray,
    passwordWm: Long = 1L,
    passwordImg: Long = 1L
): IntArray {
    require(pixels.size == width * height) { "pixels size mismatch" }
    if (payload.isEmpty()) return pixels.copyOf()
    val img = ArgbImage(pixels, width, height)
    val core = WatermarkCore(passwordImg)
    core.readImage(img)
    val wmBits = bytesToBits(payload)
    if (core.blockNum <= wmBits.size) return pixels.copyOf() // 图太小嵌不下：原样返回
    val shuffled = shuffleBits(wmBits, passwordWm)
    core.embedCore(shuffled)
    return img.toPixels()
}
