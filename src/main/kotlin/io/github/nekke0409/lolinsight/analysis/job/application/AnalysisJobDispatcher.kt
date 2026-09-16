package io.github.nekke0409.lolinsight.analysis.job.application

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component

@Component
class AnalysisJobDispatcher(
    @Qualifier(PLAYER_ANALYSIS_JOB_EXECUTOR)
    private val executor: TaskExecutor,
    private val worker: AnalysisJobWorker,
) {
    fun dispatch(command: AnalysisJobCommand) {
        executor.execute { worker.process(command) }
    }
}
