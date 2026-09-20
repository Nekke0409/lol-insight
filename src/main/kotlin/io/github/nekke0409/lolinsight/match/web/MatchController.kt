package io.github.nekke0409.lolinsight.match.web

import io.github.nekke0409.lolinsight.match.application.MatchResponse
import io.github.nekke0409.lolinsight.match.application.MatchService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ProblemDetail
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/matches")
@Tag(name = "경기", description = "Match ID 기반 경기 상세 조회 API")
class MatchController(
    private val matchService: MatchService,
) {
    @GetMapping("/{matchId}")
    @Operation(summary = "경기 상세 조회", description = "Riot Match ID로 경기와 참가자 상세 정보를 조회합니다.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "경기 조회 성공",
                content = [Content(schema = Schema(implementation = MatchResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "빈 matchId 등 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "경기를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "429",
                description = "Riot API rate limit 또는 local cooldown",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "Riot API 응답 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "Riot API 일시적 연결 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun findByMatchId(
        @Parameter(description = "Riot Match ID", example = "KR_1234567890") @PathVariable @NotBlank matchId: String,
    ): MatchResponse = matchService.findByMatchId(matchId)
}
