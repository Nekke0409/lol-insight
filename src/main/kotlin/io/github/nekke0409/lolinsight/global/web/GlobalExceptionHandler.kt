package io.github.nekke0409.lolinsight.global.web

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisAuthenticationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisConfigurationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInvalidResponseException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisTransportException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCapacityExceededException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobNotFoundException
import io.github.nekke0409.lolinsight.global.riot.RiotApiEmptyResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiInvalidResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.player.application.PlayerNotFoundException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(AnalysisJobNotFoundException::class)
    fun handleAnalysisJobNotFoundException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Analysis job not found.")

    @ExceptionHandler(AnalysisJobCapacityExceededException::class)
    fun handleAnalysisJobCapacityExceededException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AI analysis is temporarily at capacity.")

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
