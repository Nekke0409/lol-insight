package io.github.nekke0409.lolinsight.analysis.job.web

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analysis-jobs")
class AnalysisJobController(
    private val analysisJobQueryService: AnalysisJobQueryService,
) {
    @GetMapping("/{jobId}")
    fun find(
        @PathVariable jobId: UUID,
    ): AnalysisJobView = analysisJobQueryService.find(jobId)
}
