package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import java.time.Instant
import java.util.UUID

data class AnalysisJobCommand(
    val jobId: UUID,
    val gameName: String,
    val tagLine: String,
    val start: Int,
    val count: Int,
    val dedupeKey: AnalysisJobDedupeKey?,
)

data class AnalysisJobCreated(
    val jobId: UUID,
    val status: AnalysisJobStatus,
    val createdAt: Instant,
)

data class AnalysisJobView(
    val jobId: UUID,
    val status: AnalysisJobStatus,
    val result: PlayerAnalysisResult?,
    val failureCode: AnalysisJobFailureCode?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    init {
        when (status) {
            AnalysisJobStatus.PENDING -> {
                require(result == null && failureCode == null && startedAt == null && completedAt == null)
            }
            AnalysisJobStatus.RUNNING -> {
                require(result == null && failureCode == null && startedAt != null && completedAt == null)
            }
            AnalysisJobStatus.SUCCEEDED -> {
                require(result != null && failureCode == null && startedAt != null && completedAt != null)
            }
            AnalysisJobStatus.FAILED -> {
                require(result == null && failureCode != null && completedAt != null)
            }
        }
    }
}

enum class AnalysisJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class AnalysisJobFailureCode {
    CONFIGURATION,
    AUTHENTICATION,
    RATE_LIMITED,
    UPSTREAM_UNAVAILABLE,
    TIMEOUT_NETWORK,
    MALFORMED_RESPONSE,
    CAPACITY_EXCEEDED,
    TARGET_NOT_FOUND,
    UNRANKED,
    INSUFFICIENT_COMPARISON_DATA,
    INTERNAL_ERROR,
}

class AnalysisJobNotFoundException(
    jobId: UUID,
) : RuntimeException("Analysis job not found: $jobId")

class AnalysisJobCapacityExceededException : RuntimeException("Analysis job capacity exceeded")
