package com.maodouchat.watermark

/**
 * 9.4xx：blind_watermark（github.com/guofei9987/blind_watermark）核心算法的 Kotlin 忠实移植。
 *
 * 与原版 `bwm_core.py` / `blind_watermark.py` 一一对应：
 * - read_img_arr：BGR→YUV → 补白边至偶数 → 每通道 2D Haar DWT，取 LL（ca）子带
 * - 4×4 分块（仅整除部分），每块：2D DCT → 按 password_img 种子置换打乱 →
 *   SVD → 对 s[0]/s[1] 量化嵌入（d1=36 / d2=20）→ 重建 → 逆置换 → IDCT
 * - 水印位流先用 password_wm 种子洗牌（Fisher-Yates，等价 numpy RandomState.shuffle）
 * - 提取：每块 DCT→置换→SVD，由 s[0]%d1、s[1]%d2 判位，3 通道 × 循环重复求平均，
 *   一维 kmeans 二值化，逆洗牌还原位序，拼回字节
 *
 * 与原版差异（仅影响数值、不影响自洽往返）：DCT 用正交基；SVD 用单边 Jacobi；
 * RNG 用 java.util.Random（同一 seed 两侧一致即可）。
 */
object ReferenceBlindWatermark {
    const val BLOCK = 4
    const val D1 = 36.0
    const val D2 = 20.0

    // 公开 API：只留调度，实现已搬入 BlindWatermarkBitstream/BlindWatermarkPixels。

    /** 在 ARGB 像素上嵌入 [payload]（8bit/字节展开位流），返回新像素数组；原数组不变。 */
    fun embedPixels(
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
        val wmBits = BlindWatermarkBitstream.bytesToBits(payload)
        if (core.blockNum <= wmBits.size) return pixels.copyOf() // 图太小嵌不下：原样返回
        val shuffled = BlindWatermarkBitstream.shuffleBits(wmBits, passwordWm)
        core.embedCore(shuffled)
        return img.toPixels()
    }

    /** 提取 [payloadBitCount] 位载荷；无水印/图像过小返回 null。 */
    fun extractPayload(
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
        val (bits, centers) = BlindWatermarkBitstream.oneDimKmeans(wmAvg)
        // 9.4xx：置信度门——真实水印的软比特在平均后紧密聚在 0/1 两侧（类中心分离大）；
        // 无水印图片的软比特近似均匀噪声，kmeans 硬分后类中心分离小。
        // 分离度过低判定无水印返回 null，避免对干净图片提取出全 1/全 0 假阳性。
        if (centers.size < 2 || centers[1] - centers[0] < 0.6) return null
        val unshuffled = BooleanArray(payloadBitCount)
        val idx = BlindWatermarkBitstream.indexShuffle(payloadBitCount, passwordWm)
        for (k in 0 until payloadBitCount) {
            unshuffled[idx[k]] = bits[k]
        }
        return BlindWatermarkBitstream.bitsToBytes(unshuffled)
    }

    // 测试沿用的 internal 入口：同签名委托，实现在 BlindWatermarkBitstream/BlindWatermarkMath。
    internal fun bytesToBits(bytes: ByteArray): BooleanArray =
        BlindWatermarkBitstream.bytesToBits(bytes)

    internal fun bitsToBytes(bits: BooleanArray): ByteArray =
        BlindWatermarkBitstream.bitsToBytes(bits)

    internal fun dct2(block: Array<FloatArray>): Array<FloatArray> =
        BlindWatermarkMath.dct2(block)

    internal fun idct2(block: Array<FloatArray>): Array<FloatArray> =
        BlindWatermarkMath.idct2(block)

    internal fun svd(a: Array<FloatArray>): Triple<Array<FloatArray>, DoubleArray, Array<FloatArray>> =
        BlindWatermarkMath.svd(a)
}
