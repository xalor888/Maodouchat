package com.maodouchat.update

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * G329c：`AppUpdateDownloadScheduler` 的**纯函数**部分测试。
 *
 * 这个 object 此前零测试覆盖。它的 `enqueue`/`cancel`/`observe` 都要真的碰
 * WorkManager（需要 `work-testing` 依赖或真跑一个 Worker），本轮**不碰**——
 * 只测两个纯函数：`progressOf(info)` 与 `errorOf(info)`。
 *
 * 可行性是实测出来的，不是假设：`androidx.work.WorkInfo` 的构造器在测试里
 * **可直接调用**（传 id / state / outputData / tags / progress / runAttemptCount /
 * generation），所以能喂一个带 `progress` 的真实 `WorkInfo` 进去。
 * 不需要新增 `work-testing` 依赖，也不需要改生产签名。
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateDownloadSchedulerTest {

    private fun workInfo(
        state: WorkInfo.State = WorkInfo.State.RUNNING,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        outputData = output,
        tags = emptySet(),
        progress = progress,
        runAttemptCount = 0,
        generation = 0,
    )

    @Test
    fun progressOfNullInfoIsZero() {
        // null（任务还没入队/已结束）→ 0，不能抛
        assert(AppUpdateDownloadScheduler.progressOf(null) == 0)
    }

    @Test
    fun progressOfInfoWithoutProgressIsZero() {
        // 入队了但还没上报进度 → 取默认 0
        assert(AppUpdateDownloadScheduler.progressOf(workInfo()) == 0)
    }

    @Test
    fun progressOfReportsTheWorkersProgress() {
        // 与 `progressData(percent)` 产出同一形状的 Data → 必须原样读回
        val data = AppUpdateDownloadScheduler.progressData(42)
        assert(AppUpdateDownloadScheduler.progressOf(workInfo(progress = data)) == 42) {
            "progress=42 应读回 42，实际 ${AppUpdateDownloadScheduler.progressOf(workInfo(progress = data))}"
        }
    }

    @Test
    fun progressDataClampsOutOfRangeValues() {
        // `percent.coerceIn(0, 100)`：负数与超界都要被夹住，
        // 否则进度条会画出界（这是纯函数里唯一的分支逻辑）。
        assert(AppUpdateDownloadScheduler.progressData(-5).getInt("progress", -1) == 0)
        assert(AppUpdateDownloadScheduler.progressData(150).getInt("progress", -1) == 100)
    }

    @Test
    fun errorOfFallsBackFromOutputToProgress() {
        // `outputData` 优先，其次 `progress`；两者都无 → null
        assert(AppUpdateDownloadScheduler.errorOf(null) == null)
        assert(AppUpdateDownloadScheduler.errorOf(workInfo()) == null)

        val fromProgress = workInfo(progress = workDataOf("error" to "boom-progress"))
        assert(AppUpdateDownloadScheduler.errorOf(fromProgress) == "boom-progress")

        val fromOutput = workInfo(
            progress = workDataOf("error" to "boom-progress"),
            output = workDataOf("error" to "boom-output"),
        )
        assert(AppUpdateDownloadScheduler.errorOf(fromOutput) == "boom-output") {
            "outputData 应优先于 progress，实际 ${AppUpdateDownloadScheduler.errorOf(fromOutput)}"
        }
    }
}
