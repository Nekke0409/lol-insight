package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.benchmark.persistence.NoDataSourceBenchmarkPersistenceTestConfiguration
import io.github.nekke0409.lolinsight.match.infrastructure.cache.MATCH_DETAIL_CACHE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(
    properties = [
        "riot.api.key=integration-test-api-key",
        "riot.api.regional-base-url=https://riot.integration.test",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@Import(
    PlayerRecentMatchesIntegrationTest.RiotApiMockServerConfiguration::class,
    NoDataSourceBenchmarkPersistenceTestConfiguration::class,
)
class PlayerRecentMatchesIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var riotApiMockServer: MockRestServiceServer

    @Autowired
    private lateinit var cacheManager: CacheManager

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        riotApiMockServer.reset()
        cacheManager.getCache(MATCH_DETAIL_CACHE)?.clear()
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @AfterEach
    fun verifyUpstreamRequests() {
        riotApiMockServer.verify()
    }

    @Test
    fun `returns recent Match summaries through the complete Riot client slice`() {
        expectAccountLookup()
        expectMatchIds("[\"KR_9002\",\"KR_9001\"]")
        expectMatchDetail("KR_9002", matchDetail("KR_9002"))
        expectMatchDetail("KR_9001", matchDetail("KR_9001"))

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/matches", "Integration Player", "TEST")
                    .param("count", "2"),
            ).andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.player.gameName").value("Integration Player"))
            .andExpect(jsonPath("$.player.tagLine").value("TEST"))
            .andExpect(jsonPath("$.page.start").value(0))
            .andExpect(jsonPath("$.page.requestedCount").value(2))
            .andExpect(jsonPath("$.page.sourceMatchCount").value(2))
            .andExpect(jsonPath("$.page.returnedCount").value(2))
            .andExpect(jsonPath("$.page.partial").value(false))
            .andExpect(jsonPath("$.page.unavailableCount").value(0))
            .andExpect(jsonPath("$.matches[0].matchId").value("KR_9002"))
            .andExpect(jsonPath("$.matches[1].matchId").value("KR_9001"))
            .andExpect(jsonPath("$.matches[0].participant.championName").value("Aatrox"))
            .andExpect(jsonPath("$.matches[0].participant.win").value(true))
            .andExpect(jsonPath("$.matches[0].participant.kills").value(8))
            .andExpect(jsonPath("$.matches[0].participant.deaths").value(2))
            .andExpect(jsonPath("$.matches[0].participant.assists").value(5))
            .andExpect(jsonPath("$.matches[0].participant.totalCs").value(192))
            .andExpect(jsonPath("$.matches[0].participant.itemIds[0]").value(3_073))
            .andExpect(jsonPath("$.matches[0].participant.itemIds[6]").value(3_364))
            .andExpect(jsonPath("$.matches[1].participant.championName").value("Aatrox"))
            .andExpect(jsonPath("$.matches[1].participant.win").value(true))
            .andExpect(jsonPath("$.matches[1].participant.kills").value(8))
            .andExpect(jsonPath("$.matches[1].participant.deaths").value(2))
            .andExpect(jsonPath("$.matches[1].participant.assists").value(5))
            .andExpect(jsonPath("$.matches[1].participant.totalCs").value(192))
            .andExpect(jsonPath("$.matches[1].participant.itemIds[0]").value(3_073))
            .andExpect(jsonPath("$.matches[1].participant.itemIds[6]").value(3_364))
    }

    @Test
    fun `omits a not found Match detail and returns a partial response through the complete Riot client slice`() {
        expectAccountLookup()
        expectMatchIds("[\"KR_404\",\"KR_9001\"]")
        riotApiMockServer
            .expect(requestTo("https://riot.integration.test/lol/match/v5/matches/KR_404"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":{\"message\":\"Data not found\",\"status_code\":404}}"),
            )
        expectMatchDetail("KR_9001", matchDetail("KR_9001"))

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/matches", "Integration Player", "TEST")
                    .param("count", "2"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.page.sourceMatchCount").value(2))
            .andExpect(jsonPath("$.page.returnedCount").value(1))
            .andExpect(jsonPath("$.page.partial").value(true))
            .andExpect(jsonPath("$.page.unavailableCount").value(1))
            .andExpect(jsonPath("$.matches[0].matchId").value("KR_9001"))
            .andExpect(jsonPath("$.matches[0].participant.championName").value("Aatrox"))
            .andExpect(jsonPath("$.matches[0].participant.totalCs").value(192))
    }

    private fun expectAccountLookup() {
        riotApiMockServer
            .expect(
                requestTo(
                    "https://riot.integration.test/riot/account/v1/accounts/by-riot-id/Integration%20Player/TEST",
                ),
            ).andExpect(header("X-Riot-Token", "integration-test-api-key"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {"puuid":"integration-test-puuid","gameName":"Integration Player","tagLine":"TEST"}
                        """.trimIndent(),
                    ),
            )
    }

    private fun expectMatchIds(responseBody: String) {
        riotApiMockServer
            .expect(
                requestTo(
                    "https://riot.integration.test/lol/match/v5/matches/by-puuid/" +
                        "integration-test-puuid/ids?start=0&count=2",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody),
            )
    }

    private fun expectMatchDetail(
        matchId: String,
        responseBody: String,
    ) {
        riotApiMockServer
            .expect(requestTo("https://riot.integration.test/lol/match/v5/matches/$matchId"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody),
            )
    }

    private fun matchDetail(matchId: String): String =
        loadFixture("riot/match/match-detail.json")
            .replace(
                "\"matchId\": \"KR_1234567890\"",
                "\"matchId\": \"$matchId\"",
            ).replace(
                "test-puuid-blue-top",
                "integration-test-puuid",
            )

    private fun loadFixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)).readText()

    @TestConfiguration(proxyBeanMethods = false)
    class RiotApiMockServerConfiguration {
        @Bean
        @Primary
        fun testCacheManager(): CacheManager = ConcurrentMapCacheManager(MATCH_DETAIL_CACHE)

        @Bean
        fun riotApiMockTransport(): RiotApiMockTransport = RiotApiMockTransport()

        @Bean
        fun riotApiMockServer(transport: RiotApiMockTransport): MockRestServiceServer = transport.server

        @Bean
        @Primary
        fun testRiotApiRestClient(transport: RiotApiMockTransport): RestClient =
            transport.restClientBuilder
                .defaultHeader("X-Riot-Token", "integration-test-api-key")
                .build()
    }

    class RiotApiMockTransport {
        val restClientBuilder: RestClient.Builder = RestClient.builder()
        val server: MockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build()
    }
}
