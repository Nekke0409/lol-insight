package io.github.nekke0409.lolinsight.global.web

import io.github.nekke0409.lolinsight.global.riot.RiotApiEmptyResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(RiotApiResponseException::class, RiotApiEmptyResponseException::class)
    fun handleRiotApiResponseException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            "Unable to retrieve player information from Riot Games.",
        )

    @ExceptionHandler(RiotApiTransportException::class)
    fun handleRiotApiTransportException(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Riot Games is temporarily unavailable.",
        )
}
