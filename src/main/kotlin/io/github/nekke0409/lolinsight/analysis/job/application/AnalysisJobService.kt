package io.github.nekke0409.lolinsight.analysis.job.application

import org.springframework.stereotype.Service
import java.util.concurrent.RejectedExecutionException

@Service
class AnalysisJobService(
    private val lifecycleService: AnalysisJobLifecycleService,
    private val dispatcher: AnalysisJobDispatcher,
    private val inFlightRegistry: AnalysisJobInFlightRegistry,
) {
    fun create(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
        dedupeKey: AnalysisJobDedupeKey,
    ): AnalysisJobCreated {
        while (true) {
            when (
                val acquisition =
                    inFlightRegistry.acquire(dedupeKey) {
                        lifecycleService.createPending()
                    }
            ) {
                is AnalysisJobInFlightRegistry.Acquisition.Created -> {
                    val created = acquisition.entry.created
                    val command = AnalysisJobCommand(created.jobId, gameName, tagLine, start, count, dedupeKey)

                    try {
                        dispatcher.dispatch(command)
                    } catch (_: RejectedExecutionException) {
                        try {
                            lifecycleService.markRejectedIfPending(created.jobId)
                        } finally {
                            inFlightRegistry.reject(dedupeKey, acquisition.entry)
                        }
                        throw AnalysisJobCapacityExceededException()
                    }

                    inFlightRegistry.markDispatched(acquisition.entry)
                    return created
                }

                is AnalysisJobInFlightRegistry.Acquisition.Existing -> {
                    acquisition.job?.let { return it }
                    inFlightRegistry.awaitDispatch(acquisition.entry)
                    lifecycleService.findInFlight(acquisition.entry.jobId)?.let { return it }
                    inFlightRegistry.remove(dedupeKey, acquisition.entry.jobId)
                }
            }
        }
    }

    /**
     * Creates an asynchronous job for a backend-determined event.
     *
     * Automation has no HTTP client identity, so it intentionally does not participate in the
     * HTTP in-flight registry. Its persisted trigger record provides the idempotency boundary.
     */
    fun createFromAutomation(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): AnalysisJobCreated {
        val created = lifecycleService.createPending()
        val command = AnalysisJobCommand(created.jobId, gameName, tagLine, start, count, dedupeKey = null)

        try {
            dispatcher.dispatch(command)
        } catch (_: RejectedExecutionException) {
            lifecycleService.markRejectedIfPending(created.jobId)
            throw AnalysisJobCapacityExceededException()
        }

        return created
    }
}
