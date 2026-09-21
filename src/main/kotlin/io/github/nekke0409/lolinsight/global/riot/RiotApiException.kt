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
    val rateLimitType: String? = null,
) : RiotApiException("Riot API responded with HTTP ${statusCode.value()}")

class RiotApiCooldownException(
    val retryAfterSeconds: Long,
) : RiotApiException("Riot API requests are temporarily paused after a rate limit response")

sealed class RiotApiOutboundAdmissionException(
    message: String,
    cause: Throwable? = null,
) : RiotApiException(message, cause)

class RiotApiOutboundPacingTimeoutException :
    RiotApiOutboundAdmissionException("Riot API request was not admitted before the local pacing deadline")

class RiotApiOutboundPacingInterruptedException(
    cause: InterruptedException,
) : RiotApiOutboundAdmissionException("Riot API request was interrupted while waiting for local pacing", cause)

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

fun RiotApiException.requiresCollectionStop(): Boolean = isRateLimited() || this is RiotApiOutboundAdmissionException

fun RiotApiException.rateLimitRetryAfterSeconds(): Long? =
    when (this) {
        is RiotApiCooldownException -> retryAfterSeconds
        is RiotApiResponseException -> retryAfterSeconds
        else -> null
    }
