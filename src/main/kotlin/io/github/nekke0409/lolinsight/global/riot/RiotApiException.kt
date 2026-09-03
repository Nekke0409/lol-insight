package io.github.nekke0409.lolinsight.global.riot

import org.springframework.http.HttpStatusCode

sealed class RiotApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class RiotApiResponseException(
    val statusCode: HttpStatusCode,
    val responseBody: String,
) : RiotApiException("Riot API responded with HTTP ${statusCode.value()}")

class RiotApiTransportException(
    cause: Throwable,
) : RiotApiException("Riot API request failed before receiving a response", cause)

class RiotApiEmptyResponseException : RiotApiException("Riot API returned an empty response body")
