package io.github.nekke0409.lolinsight.global.riot

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RiotApiHttpClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: RiotApiHttpClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()

        client =
            RiotApiHttpClient(
                restClient = builder.defaultHeader("X-Riot-Token", "test-api-key").build(),
                properties =
                    RiotApiProperties(
                        key = "test-api-key",
                        platformBaseUrl = java.net.URI.create("https://platform.test"),
                        regionalBaseUrl = java.net.URI.create("https://regional.test"),
                    ),
            )
    }

    @Test
    fun `regional request resolves encoded path and applies Riot token header`() {
        server
            .expect(requestTo("https://regional.test/riot/account/v1/accounts/by-riot-id/Hide%20on%20bush/KR1?queue=420"))
            .andExpect(header("X-Riot-Token", "test-api-key"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"puuid\":\"test-puuid\"}"),
            )

        val response =
            client.get(
                routing = RiotApiRouting.REGIONAL,
                path = "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}",
                uriVariables = mapOf("gameName" to "Hide on bush", "tagLine" to "KR1"),
                queryParameters = mapOf("queue" to 420),
                responseType = String::class.java,
            )

        assertEquals("{\"puuid\":\"test-puuid\"}", response)
        server.verify()
    }

    @Test
    fun `error response is converted to Riot API response exception`() {
        server
            .expect(requestTo("https://platform.test/lol/summoner/v4/summoners/by-puuid/test-puuid"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":{\"message\":\"Rate limit exceeded\",\"status_code\":429}}"),
            )

        val exception =
            assertFailsWith<RiotApiResponseException> {
                client.get(
                    routing = RiotApiRouting.PLATFORM,
                    path = "/lol/summoner/v4/summoners/by-puuid/{puuid}",
                    uriVariables = mapOf("puuid" to "test-puuid"),
                    responseType = String::class.java,
                )
            }

        assertEquals(429, exception.statusCode.value())
        assertEquals("{\"status\":{\"message\":\"Rate limit exceeded\",\"status_code\":429}}", exception.responseBody)
        assertEquals(1, exception.retryAfterSeconds)
        server.verify()
    }

    @Test
    fun `missing or invalid Retry-After is ignored without replacing the Riot response exception`() {
        server
            .expect(requestTo("https://platform.test/missing-retry-after"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server
            .expect(requestTo("https://platform.test/invalid-retry-after"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "not-a-number"),
            )

        listOf("/missing-retry-after", "/invalid-retry-after").forEach { path ->
            val exception =
                assertFailsWith<RiotApiResponseException> {
                    client.get(
                        routing = RiotApiRouting.PLATFORM,
                        path = path,
                        responseType = String::class.java,
                    )
                }

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
            assertNull(exception.retryAfterSeconds)
        }

        server.verify()
    }

    @Test
    fun `read timeout is applied to RestClient and converted to transport exception`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/delayed") { exchange ->
            Thread.sleep(500)
            exchange.sendResponseHeaders(HttpStatus.OK.value(), 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()

        try {
            val baseUrl = URI.create("http://127.0.0.1:${server.address.port}")
            val properties =
                RiotApiProperties(
                    key = "test-api-key",
                    platformBaseUrl = baseUrl,
                    regionalBaseUrl = baseUrl,
                    connectTimeout = Duration.ofMillis(100),
                    readTimeout = Duration.ofMillis(100),
                )
            val timeoutClient =
                RiotApiHttpClient(
                    restClient = RiotApiConfiguration().riotApiRestClient(properties),
                    properties = properties,
                )

            val exception =
                assertFailsWith<RiotApiTransportException> {
                    timeoutClient.get(
                        routing = RiotApiRouting.REGIONAL,
                        path = "/delayed",
                        responseType = String::class.java,
                    )
                }

            assertIs<RestClientException>(exception.cause)
        } finally {
            server.stop(0)
        }
    }
}
