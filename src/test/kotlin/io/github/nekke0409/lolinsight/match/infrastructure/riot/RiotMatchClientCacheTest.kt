package io.github.nekke0409.lolinsight.match.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiEmptyResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiInvalidResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiRouting
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.infrastructure.cache.MATCH_DETAIL_CACHE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.client.RestClientException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [RiotMatchClientCacheTest.CacheTestConfiguration::class])
class RiotMatchClientCacheTest {
    @Autowired
    private lateinit var client: RiotMatchClient

    @Autowired
    private lateinit var riotApiHttpClient: RiotApiHttpClient

    @Autowired
    private lateinit var cacheManager: CacheManager

    @BeforeEach
    fun clearCache() {
        cacheManager.getCache(MATCH_DETAIL_CACHE)?.clear()
    }

    @Test
    fun `stores successful Match Details on miss reuses them on hit and separates Match IDs`() {
        stubMatchDetail("KR_123")
        stubMatchDetail("KR_456")

        val first = client.findMatchById("KR_123")
        client.findMatchById("KR_456")
        val cached = client.findMatchById("KR_123")

        assertEquals(first, cached)
        verifyMatchDetailRequest("KR_123", 1)
        verifyMatchDetailRequest("KR_456", 1)
    }

    @Test
    fun `does not cache not found rate limit server transport empty or invalid upstream failures`() {
        stubMatchDetailFailure("KR_404", RiotApiResponseException(HttpStatus.NOT_FOUND, "not found"))
        repeat(2) {
            assertFailsWith<MatchNotFoundException> {
                client.findMatchById("KR_404")
            }
        }
        verifyMatchDetailRequest("KR_404", 2)

        stubMatchDetailFailure("KR_429", RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited"))
        repeat(2) {
            assertFailsWith<RiotApiResponseException> {
                client.findMatchById("KR_429")
            }
        }
        verifyMatchDetailRequest("KR_429", 2)

        stubMatchDetailFailure("KR_500", RiotApiResponseException(HttpStatus.INTERNAL_SERVER_ERROR, "server failure"))
        repeat(2) {
            assertFailsWith<RiotApiResponseException> {
                client.findMatchById("KR_500")
            }
        }
        verifyMatchDetailRequest("KR_500", 2)

        stubMatchDetailFailure("KR_transport", RiotApiTransportException(RestClientException("connect timed out")))
        repeat(2) {
            assertFailsWith<RiotApiTransportException> {
                client.findMatchById("KR_transport")
            }
        }
        verifyMatchDetailRequest("KR_transport", 2)

        stubMatchDetailFailure("KR_empty", RiotApiEmptyResponseException())
        repeat(2) {
            assertFailsWith<RiotApiEmptyResponseException> {
                client.findMatchById("KR_empty")
            }
        }
        verifyMatchDetailRequest("KR_empty", 2)

        stubMatchDetailFailure("KR_invalid", RiotApiInvalidResponseException(IllegalArgumentException("invalid JSON")))
        repeat(2) {
            assertFailsWith<RiotApiInvalidResponseException> {
                client.findMatchById("KR_invalid")
            }
        }
        verifyMatchDetailRequest("KR_invalid", 2)
    }

    private fun stubMatchDetail(matchId: String) {
        `when`(
            riotApiHttpClient.get<RiotMatchResponseDto>(
                routing = RiotApiRouting.REGIONAL,
                path = MATCH_DETAIL_PATH,
                uriVariables = mapOf("matchId" to matchId),
                responseType = RiotMatchResponseDto::class.java,
            ),
        ).thenReturn(matchResponse(matchId))
    }

    private fun stubMatchDetailFailure(
        matchId: String,
        exception: RuntimeException,
    ) {
        `when`(
            riotApiHttpClient.get<RiotMatchResponseDto>(
                routing = RiotApiRouting.REGIONAL,
                path = MATCH_DETAIL_PATH,
                uriVariables = mapOf("matchId" to matchId),
                responseType = RiotMatchResponseDto::class.java,
            ),
        ).thenThrow(exception)
    }

    private fun verifyMatchDetailRequest(
        matchId: String,
        expectedCount: Int,
    ) {
        verify(riotApiHttpClient, times(expectedCount)).get<RiotMatchResponseDto>(
            routing = RiotApiRouting.REGIONAL,
            path = MATCH_DETAIL_PATH,
            uriVariables = mapOf("matchId" to matchId),
            responseType = RiotMatchResponseDto::class.java,
        )
    }

    private fun matchResponse(matchId: String): RiotMatchResponseDto =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()
            .readValue(
                loadFixture("riot/match/match-detail.json")
                    .replace("\"matchId\": \"KR_1234567890\"", "\"matchId\": \"$matchId\""),
                RiotMatchResponseDto::class.java,
            )

    private fun loadFixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)).readText()

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    @Import(RiotMatchClient::class)
    class CacheTestConfiguration {
        @Bean
        fun cacheManager(): CacheManager = ConcurrentMapCacheManager(MATCH_DETAIL_CACHE)

        @Bean
        fun riotApiHttpClient(): RiotApiHttpClient = mock(RiotApiHttpClient::class.java)
    }

    private companion object {
        const val MATCH_DETAIL_PATH = "/lol/match/v5/matches/{matchId}"
    }
}
