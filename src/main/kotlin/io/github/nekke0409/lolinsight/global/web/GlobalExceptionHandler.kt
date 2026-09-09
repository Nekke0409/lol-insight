package io.github.nekke0409.lolinsight.global.web

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
