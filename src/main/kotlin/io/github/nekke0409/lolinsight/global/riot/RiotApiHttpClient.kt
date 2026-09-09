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

        return try {
            restClient
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response ->
                    throw RiotApiResponseException(
                        statusCode = response.statusCode,
                        responseBody = response.body.readAllBytes().toString(StandardCharsets.UTF_8),
                        retryAfterSeconds = parseRetryAfterSeconds(response.headers.getFirst("Retry-After")),
                    )
                }.body(responseType)
                ?: throw RiotApiEmptyResponseException()
        } catch (exception: RiotApiException) {
            throw exception
        } catch (exception: ResourceAccessException) {
            throw RiotApiTransportException(exception)
        } catch (exception: RestClientException) {
            throw RiotApiInvalidResponseException(exception)
        }
    }

    private fun parseRetryAfterSeconds(retryAfter: String?): Long? =
        retryAfter
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
}
