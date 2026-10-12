package com.maodouchat.server.watermark

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
}
