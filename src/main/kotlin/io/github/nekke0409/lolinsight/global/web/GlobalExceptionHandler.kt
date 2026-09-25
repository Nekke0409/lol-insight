package io.github.nekke0409.lolinsight.global.web

import io.github.nekke0409.lolinsight.agent.application.AgentFeatureDisabledException
import io.github.nekke0409.lolinsight.agent.application.AgentModelAuthenticationException
import io.github.nekke0409.lolinsight.agent.application.AgentModelConfigurationException
import io.github.nekke0409.lolinsight.agent.application.AgentModelIncompleteResponseException
import io.github.nekke0409.lolinsight.agent.application.AgentModelInvalidResponseException
import io.github.nekke0409.lolinsight.agent.application.AgentModelProviderException
import io.github.nekke0409.lolinsight.agent.application.AgentModelRateLimitException
import io.github.nekke0409.lolinsight.agent.application.AgentModelRefusalException
import io.github.nekke0409.lolinsight.agent.application.AgentModelTransportException
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionTooLongException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisAuthenticationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisConfigurationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInvalidResponseException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisTransportException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCapacityExceededException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobNotFoundException
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimitExceededException
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldownException
import io.github.nekke0409.lolinsight.global.riot.RiotApiEmptyResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiInvalidResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.player.application.PlayerNotFoundException
import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeBudgetExceededException
import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeExecutionPlanException
import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeInputMismatchException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerConfigurationException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerDeadlineExceededException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerFeatureDisabledException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerIncompleteException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerInvalidResponseException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerRefusalException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionValidationException
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.PatchNoteAnswerAuthenticationException
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.PatchNoteAnswerProviderException
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.PatchNoteAnswerRateLimitException
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.PatchNoteAnswerTransportException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(PatchNoteAnswerFeatureDisabledException::class)
    fun handlePatchNoteAnswerFeatureDisabled(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Patch-note answers are not enabled.")

    @ExceptionHandler(PatchNoteQuestionValidationException::class)
    fun handlePatchNoteQuestionValidation(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Patch-note question is invalid.")

    @ExceptionHandler(ManualRagSmokeBudgetExceededException::class)
    fun handleManualRagSmokeBudgetExceeded(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Patch-note manual smoke budget is exhausted.")

    @ExceptionHandler(ManualRagSmokeInputMismatchException::class)
    fun handleManualRagSmokeInputMismatch(exception: ManualRagSmokeInputMismatchException): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Patch-note manual input does not match the approved plan.",
            ).apply {
                setProperty("code", "MANUAL_INPUT_MISMATCH")
                setProperty("reason", exception.reason.name)
            }

    @ExceptionHandler(ManualRagSmokeExecutionPlanException::class)
    fun handleManualRagSmokeExecutionPlan(): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Patch-note manual execution plan rejected this request.",
            ).apply {
                setProperty("code", "MANUAL_EXECUTION_PLAN_REJECTED")
            }

    @ExceptionHandler(PatchNoteAnswerConfigurationException::class)
    fun handlePatchNoteAnswerConfiguration(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Patch-note answers are not configured.")

    @ExceptionHandler(
        PatchNoteAnswerAuthenticationException::class,
        PatchNoteAnswerProviderException::class,
        PatchNoteAnswerInvalidResponseException::class,
    )
    fun handlePatchNoteAnswerProvider(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Unable to generate a patch-note answer.")

    @ExceptionHandler(PatchNoteAnswerIncompleteException::class)
    fun handlePatchNoteAnswerIncomplete(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Patch-note answer response was incomplete.")

    @ExceptionHandler(PatchNoteAnswerRefusalException::class)
    fun handlePatchNoteAnswerRefusal(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Patch-note answer was refused.")

    @ExceptionHandler(PatchNoteAnswerRateLimitException::class)
    fun handlePatchNoteAnswerRateLimit(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Patch-note answer provider is temporarily rate limited.")

    @ExceptionHandler(PatchNoteAnswerTransportException::class, PatchNoteAnswerDeadlineExceededException::class)
    fun handlePatchNoteAnswerUnavailable(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Patch-note answer provider is temporarily unavailable.")

    @ExceptionHandler(AgentFeatureDisabledException::class)
    fun handleAgentFeatureDisabledException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "AI agent is not enabled.")

    @ExceptionHandler(AgentQuestionTooLongException::class)
    fun handleAgentQuestionTooLongException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Agent question exceeds the allowed length.")

    @ExceptionHandler(AgentModelConfigurationException::class)
    fun handleAgentModelConfigurationException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI agent is not configured.")

    @ExceptionHandler(AgentModelAuthenticationException::class)
    fun handleAgentModelAuthenticationException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "AI agent provider rejected the request.")

    @ExceptionHandler(AgentModelRateLimitException::class)
    fun handleAgentModelRateLimitException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "AI agent is temporarily rate limited.")

    @ExceptionHandler(AgentModelProviderException::class, AgentModelInvalidResponseException::class)
    fun handleAgentModelProviderException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Unable to generate an AI agent response.")

    @ExceptionHandler(AgentModelIncompleteResponseException::class)
    fun handleAgentModelIncompleteResponseException(exception: AgentModelIncompleteResponseException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "AI agent response was incomplete.").apply {
            setProperty("code", "AGENT_MODEL_RESPONSE_INCOMPLETE")
            exception.incompleteReason?.let { setProperty("reason", it.name) }
        }

    @ExceptionHandler(AgentModelRefusalException::class)
    fun handleAgentModelRefusalException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "AI agent declined this request.")

    @ExceptionHandler(AgentModelTransportException::class)
    fun handleAgentModelTransportException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI agent provider is temporarily unavailable.")

    @ExceptionHandler(AnalysisJobNotFoundException::class)
    fun handleAnalysisJobNotFoundException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Analysis job not found.")

    @ExceptionHandler(AnalysisJobCapacityExceededException::class)
    fun handleAnalysisJobCapacityExceededException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI analysis is temporarily at capacity.")

    @ExceptionHandler(AnalysisGenerationRateLimitExceededException::class)
    fun handleAnalysisGenerationRateLimitExceededException(
        exception: AnalysisGenerationRateLimitExceededException,
    ): ResponseEntity<ProblemDetail> {
        val problemDetail =
            ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "AI analysis generation rate limit exceeded.").apply {
                setProperty("code", "ANALYSIS_RATE_LIMIT_EXCEEDED")
            }

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            .body(problemDetail)
    }

    @ExceptionHandler(PlayerAnalysisConfigurationException::class)
    fun handlePlayerAnalysisConfigurationException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI analysis is not configured.")

    @ExceptionHandler(PlayerAnalysisAuthenticationException::class)
    fun handlePlayerAnalysisAuthenticationException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "AI analysis provider rejected the request.")

    @ExceptionHandler(PlayerAnalysisRateLimitException::class)
    fun handlePlayerAnalysisRateLimitException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "AI analysis is temporarily rate limited.")

    @ExceptionHandler(PlayerAnalysisProviderException::class, PlayerAnalysisInvalidResponseException::class)
    fun handlePlayerAnalysisProviderException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Unable to generate AI analysis.")

    @ExceptionHandler(PlayerAnalysisTransportException::class)
    fun handlePlayerAnalysisTransportException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI analysis provider is temporarily unavailable.")

    @ExceptionHandler(PlayerNotFoundException::class)
    fun handlePlayerNotFoundException(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Player not found.")

    @ExceptionHandler(MatchNotFoundException::class)
    fun handleMatchNotFoundException(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Match not found.")

    @ExceptionHandler(RiotApiCooldownException::class)
    fun handleRiotApiCooldownException(exception: RiotApiCooldownException): ResponseEntity<ProblemDetail> {
        val problemDetail =
            ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Riot Games requests are temporarily rate limited.").apply {
                setProperty("code", "RIOT_API_COOLDOWN_ACTIVE")
            }

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            .body(problemDetail)
    }

    @ExceptionHandler(RiotApiResponseException::class)
    fun handleRiotApiResponseException(exception: RiotApiResponseException): ResponseEntity<ProblemDetail> {
        val status =
            if (exception.statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                HttpStatus.TOO_MANY_REQUESTS
            } else {
                HttpStatus.BAD_GATEWAY
            }
        val problemDetail =
            ProblemDetail.forStatusAndDetail(
                status,
                "Unable to retrieve data from Riot Games.",
            )

        return if (status == HttpStatus.TOO_MANY_REQUESTS && exception.retryAfterSeconds != null) {
            ResponseEntity
                .status(status)
                .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
                .body(problemDetail)
        } else {
            ResponseEntity.status(status).body(problemDetail)
        }
    }

    @ExceptionHandler(RiotApiEmptyResponseException::class, RiotApiInvalidResponseException::class)
    fun handleRiotApiInvalidResponseException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            "Unable to retrieve data from Riot Games.",
        )

    @ExceptionHandler(RiotApiTransportException::class)
    fun handleRiotApiTransportException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Riot Games is temporarily unavailable.",
        )
}
