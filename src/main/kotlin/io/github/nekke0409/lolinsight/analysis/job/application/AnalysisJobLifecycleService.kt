package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobEntity
import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class AnalysisJobLifecycleService(
    private val analysisJobJpaRepository: AnalysisJobJpaRepository,
    private val resultCodec: AnalysisJobResultCodec,
    private val clock: Clock,
) {
    @Transactional
    fun createPending(): AnalysisJobCreated {
        val createdAt = Instant.now(clock)
        val entity =
            analysisJobJpaRepository.save(
                AnalysisJobEntity(
                    id = UUID.randomUUID(),
                    status = AnalysisJobStatus.PENDING,
                    createdAt = createdAt,
                ),
            )

        return AnalysisJobCreated(entity.id, entity.status, entity.createdAt)
    }

    @Transactional
    fun markRunningIfPending(jobId: UUID): Boolean =
        analysisJobJpaRepository.markRunningIfPending(
            jobId = jobId,
            pendingStatus = AnalysisJobStatus.PENDING,
            runningStatus = AnalysisJobStatus.RUNNING,
            startedAt = Instant.now(clock),
        ) == 1

    @Transactional
    fun markSucceededIfRunning(
        jobId: UUID,
        result: PlayerAnalysisResult,
    ): Boolean =
        analysisJobJpaRepository.markSucceededIfRunning(
            jobId = jobId,
            runningStatus = AnalysisJobStatus.RUNNING,
            succeededStatus = AnalysisJobStatus.SUCCEEDED,
            result = resultCodec.write(result),
            completedAt = Instant.now(clock),
        ) == 1

    @Transactional
    fun markFailedIfRunning(
        jobId: UUID,
        failureCode: AnalysisJobFailureCode,
    ): Boolean =
        analysisJobJpaRepository.markFailedIfRunning(
            jobId = jobId,
            runningStatus = AnalysisJobStatus.RUNNING,
            failedStatus = AnalysisJobStatus.FAILED,
            failureCode = failureCode,
            completedAt = Instant.now(clock),
        ) == 1

    @Transactional
    fun markRejectedIfPending(jobId: UUID): Boolean =
        analysisJobJpaRepository.markRejectedIfPending(
            jobId = jobId,
            pendingStatus = AnalysisJobStatus.PENDING,
            failedStatus = AnalysisJobStatus.FAILED,
            failureCode = AnalysisJobFailureCode.CAPACITY_EXCEEDED,
            completedAt = Instant.now(clock),
        ) == 1
}
