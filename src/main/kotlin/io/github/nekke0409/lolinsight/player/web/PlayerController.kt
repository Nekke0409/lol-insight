package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponse
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCreated
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobDedupeKey
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobService
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryService
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsResponse
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsService
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import io.github.nekke0409.lolinsight.player.application.RecentMatchesResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/players")
@Tag(name = "플레이어", description = "Riot ID 기반 플레이어, 전적, 통계와 분석 API")
class PlayerController(
    private val playerService: PlayerService,
    private val playerMatchHistoryService: PlayerMatchHistoryService,
    private val playerMatchStatisticsService: PlayerMatchStatisticsService,
    private val playerAnalysisService: PlayerAnalysisService,
    private val analysisJobService: AnalysisJobService,
    private val analysisRateLimitKeyResolver: AnalysisRateLimitKeyResolver,
    private val analysisGenerationRateLimiter: AnalysisGenerationRateLimiter,
) {
    @GetMapping("/{gameName}/{tagLine}")
    @Operation(
        summary = "플레이어 조회",
        description = "Riot ID의 gameName과 tagLine으로 플레이어를 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "플레이어 조회 성공",
                content = [Content(schema = Schema(implementation = PlayerResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "빈 경로 값 등 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "플레이어를 찾을 수 없음",
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
    fun findByRiotId(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
    ): PlayerResponse = playerService.findByRiotId(gameName, tagLine)

    @GetMapping("/{gameName}/{tagLine}/matches")
    @Operation(
        summary = "최근 경기 조회",
        description = "Match-V5의 전체 최근 경기 목록을 페이지 단위로 조회합니다. queue로 제한하지 않으므로 일반, 랭크, ARAM 등 반환 가능한 경기 모두가 대상입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "최근 경기 조회 성공",
                content = [Content(schema = Schema(implementation = RecentMatchesResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "경로 또는 pagination 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "플레이어를 찾을 수 없음",
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
    fun findRecentMatches(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
        @Parameter(description = "최근 경기 목록에서 건너뛸 시작 offset") @RequestParam(defaultValue = "0") @Min(0) start: Int,
        @Parameter(description = "조회할 경기 수") @RequestParam(defaultValue = "20") @Min(1) @Max(20) count: Int,
    ): RecentMatchesResponse = playerMatchHistoryService.findRecentMatches(gameName, tagLine, start, count)

    @GetMapping("/{gameName}/{tagLine}/stats")
    @Operation(
        summary = "최근 경기 통계 조회",
        description = "Match-V5의 전체 최근 경기를 집계한 일반 통계입니다. Ranked Solo만 대상으로 하는 분석 API와 달리 queue 필터를 적용하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "통계 조회 성공",
                content = [Content(schema = Schema(implementation = PlayerMatchStatisticsResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "경로 또는 pagination 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "플레이어를 찾을 수 없음",
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
    fun findMatchStatistics(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
        @Parameter(description = "최근 경기 목록에서 건너뛸 시작 offset") @RequestParam(defaultValue = "0") @Min(0) start: Int,
        @Parameter(description = "조회할 경기 수") @RequestParam(defaultValue = "20") @Min(1) @Max(20) count: Int,
    ): PlayerMatchStatisticsResponse = playerMatchStatisticsService.findStatistics(gameName, tagLine, start, count)

    @PostMapping("/{gameName}/{tagLine}/analysis")
    @Operation(
        summary = "동기 플레이 분석",
        description = "최근 Ranked Solo만 분석하고 결과를 즉시 반환합니다. start와 count는 필터링 후 목록의 pagination이며, 호출 시 외부 API 비용과 기존 제한이 적용됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "분석 완료 또는 비교 데이터 부족/미배치 상태",
                content = [Content(schema = Schema(implementation = PlayerAnalysisResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "경로 또는 pagination 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "플레이어를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "429",
                description = "분석 생성 제한, Riot API rate limit 또는 local cooldown",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "Riot 또는 AI provider 응답 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "AI provider 미설정 또는 Riot/AI provider 일시적 연결 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun analyzePlayer(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
        @Parameter(description = "Ranked Solo 경기 목록에서 건너뛸 시작 offset") @RequestParam(defaultValue = "0") @Min(0) start: Int,
        @Parameter(description = "분석할 Ranked Solo 경기 수") @RequestParam(defaultValue = "20") @Min(1) @Max(20) count: Int,
        request: HttpServletRequest,
    ): PlayerAnalysisResponse {
        analysisGenerationRateLimiter.check(analysisRateLimitKeyResolver.resolve(request))
        return playerAnalysisService.analyze(gameName, tagLine, start, count)
    }

    @PostMapping("/{gameName}/{tagLine}/analysis-jobs")
    @Operation(
        summary = "비동기 플레이 분석 작업 생성",
        description = "Ranked Solo 분석 작업을 수용하고 202와 polling URL을 반환합니다. 결과는 Location의 job 조회 API를 polling하며, 실행 시 외부 API 비용과 기존 제한이 적용됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "분석 작업 수용 성공",
                headers = [
                    io.swagger.v3.oas.annotations.headers.Header(
                        name = HttpHeaders.LOCATION,
                        description = "생성 또는 재사용된 작업을 polling할 상대 URL",
                        schema = Schema(type = "string", format = "uri-reference"),
                    ),
                ],
                content = [Content(schema = Schema(implementation = AnalysisJobCreated::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "경로 또는 pagination 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "429",
                description = "분석 생성 제한",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "분석 작업 수용 capacity 초과",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun createAnalysisJob(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
        @Parameter(description = "Ranked Solo 경기 목록에서 건너뛸 시작 offset") @RequestParam(defaultValue = "0") @Min(0) start: Int,
        @Parameter(description = "분석할 Ranked Solo 경기 수") @RequestParam(defaultValue = "20") @Min(1) @Max(20) count: Int,
        request: HttpServletRequest,
    ): ResponseEntity<AnalysisJobCreated> {
        val clientIdentity = analysisRateLimitKeyResolver.resolve(request)
        analysisGenerationRateLimiter.check(clientIdentity)
        val dedupeKey =
            AnalysisJobDedupeKey.of(
                clientIdentity,
                gameName,
                tagLine,
                start,
                count,
            )
        val created = analysisJobService.create(gameName, tagLine, start, count, dedupeKey)
        return ResponseEntity
            .accepted()
            .header(HttpHeaders.LOCATION, "/api/v1/analysis-jobs/${created.jobId}")
            .body(created)
    }
}
