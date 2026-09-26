package io.github.nekke0409.lolinsight.agent.web

import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteScope
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionResponse
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionService
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody

@RestController
@RequestMapping("/api/v1/players")
@Tag(name = "AI Agent", description = "조건부로 활성화되는 Tool-using AI Agent 질문 API")
class AgentQuestionController(
    private val agentQuestionService: AgentQuestionService,
    private val analysisRateLimitKeyResolver: AnalysisRateLimitKeyResolver,
) {
    @PostMapping("/{gameName}/{tagLine}/agent-questions")
    @Operation(
        summary = "플레이어 경기 데이터에 질문",
        description =
            "최근 Ranked Solo 통계와 peer comparison Tool을 사용하는 Agent 질문입니다. " +
                "knowledgeScope를 명시하고 Agent 패치 노트 Tool과 RAG retrieval을 모두 활성화한 경우에만 " +
                "해당 범위의 공식 패치 노트 검색 Tool을 추가합니다. 독립 RAG answer generator 설정은 필요하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Agent 답변 생성 성공",
                content = [Content(schema = Schema(implementation = AgentQuestionResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "경로 값, question 또는 knowledgeScope 입력 검증 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Agent 기능이 비활성화되었거나 플레이어를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "422",
                description = "Agent model이 요청을 거절함",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "429",
                description = "Agent 또는 Riot API rate limit",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "Agent 또는 Riot provider 응답 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "Agent 미설정 또는 provider 일시적 연결 실패",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun ask(
        @Parameter(description = "Riot ID의 게임 이름", example = "ExamplePlayer") @PathVariable @NotBlank gameName: String,
        @Parameter(description = "Riot ID의 태그. 구분자 # 없이 입력", example = "KR1") @PathVariable @NotBlank tagLine: String,
        @OpenApiRequestBody(
            description = "질문 본문. question은 공백이 아니며 최대 1,000자입니다.",
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AgentQuestionRequest::class),
                    examples = [
                        ExampleObject(value = "{\"question\":\"최근 Ranked Solo에서 개선할 점은 무엇인가요?\"}"),
                        ExampleObject(
                            value =
                                "{\"question\":\"25.10 패치에서 룰루 궁극기는 어떻게 바뀌었어?\",\"knowledgeScope\":" +
                                    "{\"patchVersion\":\"25.10\",\"locale\":\"ko-KR\"}}",
                        ),
                    ],
                ),
            ],
        )
        @org.springframework.web.bind.annotation.RequestBody
        @Valid request: AgentQuestionRequest,
        httpRequest: HttpServletRequest,
    ): AgentQuestionResponse =
        agentQuestionService.answer(
            gameName = gameName,
            tagLine = tagLine,
            question = request.question,
            clientIdentity = analysisRateLimitKeyResolver.resolve(httpRequest),
            knowledgeScope = request.knowledgeScope?.let { AgentPatchNoteScope(it.patchVersion, it.locale) },
        )
}

data class AgentQuestionRequest(
    @field:NotBlank
    @field:Size(max = 1_000)
    val question: String,
    @field:Valid
    val knowledgeScope: AgentKnowledgeScopeRequest? = null,
)

data class AgentKnowledgeScopeRequest(
    @field:NotBlank
    @field:Size(max = 10)
    val patchVersion: String,
    @field:NotBlank
    @field:Size(max = 10)
    val locale: String,
)
