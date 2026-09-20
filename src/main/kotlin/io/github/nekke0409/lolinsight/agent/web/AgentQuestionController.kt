package io.github.nekke0409.lolinsight.agent.web

import io.github.nekke0409.lolinsight.agent.application.AgentQuestionResponse
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionService
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/players")
class AgentQuestionController(
    private val agentQuestionService: AgentQuestionService,
    private val analysisRateLimitKeyResolver: AnalysisRateLimitKeyResolver,
) {
    @PostMapping("/{gameName}/{tagLine}/agent-questions")
    fun ask(
        @PathVariable @NotBlank gameName: String,
        @PathVariable @NotBlank tagLine: String,
        @RequestBody @Valid request: AgentQuestionRequest,
        httpRequest: HttpServletRequest,
    ): AgentQuestionResponse =
        agentQuestionService.answer(
            gameName = gameName,
            tagLine = tagLine,
            question = request.question,
            clientIdentity = analysisRateLimitKeyResolver.resolve(httpRequest),
        )
}

data class AgentQuestionRequest(
    @field:NotBlank
    @field:Size(max = 1_000)
    val question: String,
)
