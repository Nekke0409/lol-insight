package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.match.application.MatchDetailBatchLoader
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.assertEquals

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BenchmarkSamplePersistenceService::class)
@Testcontainers
class BenchmarkMatchCollectionIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var benchmarkSamplePersistenceService: BenchmarkSamplePersistenceService

    private lateinit var server: MockRestServiceServer
    private val matchDetailExecutor = Executors.newFixedThreadPool(4)
    private lateinit var service: BenchmarkMatchCollectionService

    @BeforeEach
    fun setUp() {
        val restClientBuilder = RestClient.builder()
        server = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build()
        val riotMatchClient =
            RiotMatchClient(
                RiotApiHttpClient(
                    restClient = restClientBuilder.build(),
                    properties =
                        RiotApiProperties(
                            key = "test-api-key",
                            platformBaseUrl = URI.create("https://kr.api.riotgames.com"),
                            regionalBaseUrl = URI.create("https://asia.api.riotgames.com"),
                        ),
                    cooldown =
                        RiotApiCooldown(
                            RiotApiProperties(key = "test-api-key"),
                            Clock.fixed(COLLECTED_AT, ZoneOffset.UTC),
                        ),
                ),
            )
        service =
            BenchmarkMatchCollectionService(
                riotMatchClient = riotMatchClient,
                matchDetailBatchLoader = MatchDetailBatchLoader(riotMatchClient, matchDetailExecutor),
                benchmarkSamplePersistenceService = benchmarkSamplePersistenceService,
                clock = Clock.fixed(COLLECTED_AT, ZoneOffset.UTC),
            )
    }

    @AfterEach
    fun tearDown() {
        matchDetailExecutor.shutdownNow()
        server.verify()
    }

    @Test
    fun `persists only sampled participants and keeps collection idempotent`() {
        expectMatchList("player-a", "[\"M1\",\"M2\"]")
        expectMatchList("player-b", "[\"M1\",\"M3\"]")
        expectMatchDetail("M1", fixture("M1", "player-a", "player-b"))
        expectMatchDetail("M2", fixture("M2", "player-a", "unsampled-player"))
        expectMatchDetail("M3", fixture("M3", "player-b", "unsampled-player"))

        val players =
            listOf(
                sampledPlayer("player-a", tier = "GOLD", division = "I"),
                sampledPlayer("player-b", tier = "PLATINUM", division = "IV"),
            )

        val first = service.collect(players, matchesPerPlayer = 2)
        val second = service.collect(players, matchesPerPlayer = 2)

        assertEquals(4, first.createdSamples)
        assertEquals(0, first.skippedDuplicates)
        assertEquals(0, first.skippedInvalidSamples)
        assertEquals(0, second.createdSamples)
        assertEquals(4, second.skippedDuplicates)
        assertEquals(4, benchmarkSampleJpaRepository.count())
        assertEquals(
            setOf("M1" to "player-a", "M1" to "player-b", "M2" to "player-a", "M3" to "player-b"),
            benchmarkSampleJpaRepository.findAll().map { it.matchId to it.puuid }.toSet(),
        )
        assertEquals("GOLD", benchmarkSampleJpaRepository.findByMatchIdAndPuuid("M1", "player-a")?.tier)
        assertEquals("PLATINUM", benchmarkSampleJpaRepository.findByMatchIdAndPuuid("M1", "player-b")?.tier)
        assertEquals(RANK_CAPTURED_AT, benchmarkSampleJpaRepository.findByMatchIdAndPuuid("M1", "player-a")?.rankCapturedAt)
        assertEquals(COLLECTED_AT, benchmarkSampleJpaRepository.findByMatchIdAndPuuid("M1", "player-a")?.collectedAt)
    }

    private fun expectMatchList(
        puuid: String,
        response: String,
    ) {
        server
            .expect(
                ExpectedCount.times(2),
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/$puuid/ids?start=0&count=2&queue=420",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response),
            )
    }

    private fun expectMatchDetail(
        matchId: String,
        response: String,
    ) {
        server
            .expect(ExpectedCount.times(2), requestTo("https://asia.api.riotgames.com/lol/match/v5/matches/$matchId"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response),
            )
    }

    private fun fixture(
        matchId: String,
        bluePuuid: String,
        redPuuid: String,
    ): String =
        loadFixture("riot/match/match-detail.json")
            .replace("KR_1234567890", matchId)
            .replace("test-puuid-blue-top", bluePuuid)
            .replace("test-puuid-red-top", redPuuid)

    private fun sampledPlayer(
        puuid: String,
        tier: String,
        division: String,
    ): SampledRankedPlayer =
        SampledRankedPlayer(
            puuid = puuid,
            region = "KR",
            queue = "RANKED_SOLO_5x5",
            tier = tier,
            division = division,
            rankCapturedAt = RANK_CAPTURED_AT,
        )

    private fun loadFixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)).readText()

    private companion object {
        val RANK_CAPTURED_AT: Instant = Instant.parse("2026-09-13T12:34:56Z")
        val COLLECTED_AT: Instant = Instant.parse("2026-09-14T00:00:00Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
