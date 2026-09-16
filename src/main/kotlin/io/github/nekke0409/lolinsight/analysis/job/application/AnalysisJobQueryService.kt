package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AnalysisJobQueryService(
    private val analysisJobJpaRepository: AnalysisJobJpaRepository,
    private val resultCodec: AnalysisJobResultCodec,
) {
    @Transactional(readOnly = true)
    fun find(jobId: UUID): AnalysisJobView {
        val job = analysisJobJpaRepository.findById(jobId).orElseThrow { AnalysisJobNotFoundException(jobId) }
        val result = job.result?.let(resultCodec::read)

        return AnalysisJobView(
            jobId = job.id,
            status = job.status,
            result = result,
            failureCode = job.failureCode,
            createdAt = job.createdAt,
            startedAt = job.startedAt,
            completedAt = job.completedAt,
        )
    }
}
