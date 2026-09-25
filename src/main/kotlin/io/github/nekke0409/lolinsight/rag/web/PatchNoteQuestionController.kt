package io.github.nekke0409.lolinsight.rag.web

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionRequest
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionResponse
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/knowledge")
class PatchNoteQuestionController(
    private val questionService: PatchNoteQuestionService,
    private val rateLimitKeyResolver: AnalysisRateLimitKeyResolver,
) {
    @PostMapping("/patch-note-questions")
    fun ask(
        @RequestBody @Valid request: PatchNoteQuestionHttpRequest,
        @RequestHeader(name = "X-Rag-Manual-Question-Id", required = false) manualQuestionId: String?,
        httpRequest: HttpServletRequest,
    ): PatchNoteQuestionResponse =
        questionService.answer(
            PatchNoteQuestionRequest(request.patchVersion, request.locale, request.question, manualQuestionId),
            rateLimitKeyResolver.resolve(httpRequest),
        )
}

data class PatchNoteQuestionHttpRequest(
    @field:NotBlank val patchVersion: String,
    @field:NotBlank val locale: String,
    @field:NotBlank @field:Size(max = 1_000) val question: String,
)
