package io.github.nekke0409.lolinsight.analysis.job.persistence

import com.fasterxml.jackson.databind.JsonNode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobFailureCode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AnalysisJobJpaRepository : JpaRepository<AnalysisJobEntity, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AnalysisJobEntity job
        set job.status = :runningStatus,
            job.startedAt = :startedAt
        where job.id = :jobId
          and job.status = :pendingStatus
        """,
    )
    fun markRunningIfPending(
        @Param("jobId") jobId: UUID,
        @Param("pendingStatus") pendingStatus: AnalysisJobStatus,
        @Param("runningStatus") runningStatus: AnalysisJobStatus,
        @Param("startedAt") startedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AnalysisJobEntity job
        set job.status = :succeededStatus,
            job.result = :result,
            job.completedAt = :completedAt
        where job.id = :jobId
          and job.status = :runningStatus
        """,
    )
    fun markSucceededIfRunning(
        @Param("jobId") jobId: UUID,
        @Param("runningStatus") runningStatus: AnalysisJobStatus,
        @Param("succeededStatus") succeededStatus: AnalysisJobStatus,
        @Param("result") result: JsonNode,
        @Param("completedAt") completedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AnalysisJobEntity job
        set job.status = :failedStatus,
            job.failureCode = :failureCode,
            job.completedAt = :completedAt
        where job.id = :jobId
          and job.status = :runningStatus
        """,
    )
    fun markFailedIfRunning(
        @Param("jobId") jobId: UUID,
        @Param("runningStatus") runningStatus: AnalysisJobStatus,
        @Param("failedStatus") failedStatus: AnalysisJobStatus,
        @Param("failureCode") failureCode: AnalysisJobFailureCode,
        @Param("completedAt") completedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AnalysisJobEntity job
        set job.status = :failedStatus,
            job.failureCode = :failureCode,
            job.completedAt = :completedAt
        where job.id = :jobId
          and job.status = :pendingStatus
        """,
    )
    fun markRejectedIfPending(
        @Param("jobId") jobId: UUID,
        @Param("pendingStatus") pendingStatus: AnalysisJobStatus,
        @Param("failedStatus") failedStatus: AnalysisJobStatus,
        @Param("failureCode") failureCode: AnalysisJobFailureCode,
        @Param("completedAt") completedAt: Instant,
    ): Int
}
