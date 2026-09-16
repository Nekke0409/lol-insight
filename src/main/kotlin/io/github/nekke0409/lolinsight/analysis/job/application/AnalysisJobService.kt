package io.github.nekke0409.lolinsight.analysis.job.application

import org.springframework.stereotype.Service
import java.util.concurrent.RejectedExecutionException

@Service
class AnalysisJobService(
    private val lifecycleService: AnalysisJobLifecycleService,
    private val dispatcher: AnalysisJobDispatcher,
) {
    fun create(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): AnalysisJobCreated {
        val created = lifecycleService.createPending()
        val command = AnalysisJobCommand(created.jobId, gameName, tagLine, start, count)

        try {
            dispatcher.dispatch(command)
        } catch (_: RejectedExecutionException) {
            lifecycleService.markRejectedIfPending(created.jobId)
            throw AnalysisJobCapacityExceededException()
        }

        return created
    }
}
