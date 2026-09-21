package io.github.nekke0409.lolinsight.global.riot

import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import java.nio.charset.StandardCharsets

@Component
class RiotApiHttpClient(
    private val restClient: RestClient,
    private val properties: RiotApiProperties,
    private val cooldown: RiotApiCooldown,
    private val outboundPacing: RiotApiOutboundPacing = RiotApiOutboundPacing { },
    private val observationRecorder: RiotApiObservationRecorder = NoOpRiotApiObservationRecorder,
) {
    fun <T : Any> get(
        routing: RiotApiRouting,
        path: String,
        uriVariables: Map<String, *> = emptyMap<String, Any>(),
        queryParameters: Map<String, Any?> = emptyMap(),
        responseType: Class<T>,
    ): T {
        require(path.startsWith('/')) { "Riot API path must start with '/'." }

        val uriBuilder = UriComponentsBuilder.fromUri(routing.baseUrl(properties)).path(path)
        queryParameters
            .filterValues { it != null }
            .forEach { (name, value) -> uriBuilder.queryParam(name, value) }

        val uri =
            uriBuilder
                .buildAndExpand(uriVariables)
                .encode()
                .toUri()

        val endpoint = endpointKind(path)
        return try {
            checkCooldownAdmission()
            outboundPacing.awaitAdmission()
            observationRecorder.recordHttpAttempt(endpoint)
            val entity =
                restClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, response ->
                        val retryAfterSeconds = parseRetryAfterSeconds(response.headers.getFirst("Retry-After"))
                        val rateLimitType = parseRateLimitType(response.headers.getFirst("X-Rate-Limit-Type"))
                        observationRecorder.recordHttpResponse(endpoint, response.statusCode.value())
                        if (response.statusCode.value() == TOO_MANY_REQUESTS) {
                            cooldown.registerRateLimit(retryAfterSeconds)
                            observationRecorder.recordUpstreamRateLimit(retryAfterSeconds, rateLimitType)
                        }
                        throw RiotApiResponseException(
                            statusCode = response.statusCode,
                            responseBody = response.body.readAllBytes().toString(StandardCharsets.UTF_8),
                            retryAfterSeconds = retryAfterSeconds,
                            rateLimitType = rateLimitType,
                        )
                    }.toEntity(responseType)
            observationRecorder.recordHttpResponse(endpoint, entity.statusCode.value())
            entity.body ?: throw RiotApiEmptyResponseException()
        } catch (exception: RiotApiException) {
            throw exception
        } catch (exception: ResourceAccessException) {
            observationRecorder.recordTransportFailure(endpoint)
            throw RiotApiTransportException(exception)
        } catch (exception: RestClientException) {
            observationRecorder.recordTransportFailure(endpoint)
            throw RiotApiInvalidResponseException(exception)
        }
    }

    private fun checkCooldownAdmission() {
        try {
            cooldown.checkAdmission()
        } catch (exception: RiotApiCooldownException) {
            observationRecorder.recordCooldownBlocked()
            throw exception
        }
    }

    private fun parseRetryAfterSeconds(retryAfter: String?): Long? =
        retryAfter
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }

    private fun parseRateLimitType(value: String?): String =
        value
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in RATE_LIMIT_TYPES }
            ?: "unknown"

    private fun endpointKind(path: String): String =
        when (path) {
            "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}" -> "account_by_riot_id"
            "/lol/league/v4/entries/{queue}/{tier}/{division}" -> "league_entries"
            "/lol/league/v4/entries/by-puuid/{puuid}" -> "league_by_puuid"
            "/lol/match/v5/matches/by-puuid/{puuid}/ids" -> "match_ids"
            "/lol/match/v5/matches/{matchId}" -> "match_detail"
            else -> "other"
        }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        val RATE_LIMIT_TYPES = setOf("application", "method", "service")
    }
}
