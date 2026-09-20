package io.github.nekke0409.lolinsight.analysis.job.web

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analysis-jobs")
@Tag(name = "분석 작업", description = "비동기 플레이 분석 작업의 polling API")
class AnalysisJobController(
    private val analysisJobQueryService: AnalysisJobQueryService,
) {
    @GetMapping("/{jobId}")
    @Operation(
        summary = "비동기 분석 작업 상태 조회",
        description = "비동기 분석 작업의 상태와 완료 결과 또는 안전한 실패 코드를 조회합니다. 작업 생성 후 Location 헤더의 URL을 polling합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "작업 상태 조회 성공",
                content = [Content(schema = Schema(implementation = AnalysisJobView::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "UUID 형식이 아닌 jobId",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "분석 작업을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun find(
        @Parameter(description = "분석 작업 UUID", example = "e8741722-84c8-4d4f-9c1b-09c7a63418cf") @PathVariable jobId: UUID,
    ): AnalysisJobView = analysisJobQueryService.find(jobId)
}
