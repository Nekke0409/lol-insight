package io.github.nekke0409.lolinsight.global.riot

import org.springframework.http.HttpStatusCode

sealed class RiotApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class RiotApiResponseException(
    val statusCode: HttpStatusCode,
    val responseBody: String,
    val retryAfterSeconds: Long? = null,
) : RiotApiException("Riot API responded with HTTP ${statusCode.value()}")

class RiotApiCooldownException(
    val retryAfterSeconds: Long,
) : RiotApiException("Riot API requests are temporarily paused after a rate limit response")

class RiotApiTransportException(
    cause: Throwable,
) : RiotApiException("Riot API request failed before receiving a response", cause)

class RiotApiEmptyResponseException : RiotApiException("Riot API returned an empty response body")

class RiotApiInvalidResponseException(
    cause: Throwable,
) : RiotApiException("Riot API returned an invalid response", cause)

fun RiotApiException.isRateLimited(): Boolean =
    this is RiotApiCooldownException ||
        (this is RiotApiResponseException && statusCode.value() == 429)

fun RiotApiException.rateLimitRetryAfterSeconds(): Long? =
    when (this) {
        is RiotApiCooldownException -> retryAfterSeconds
        is RiotApiResponseException -> retryAfterSeconds
        else -> null
    }
