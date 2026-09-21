package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkCollectionResult
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkMatchCollectionService
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkQueryWindowFactory
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSeedCandidateSelector
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSeedRequest
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSeedService
import io.github.nekke0409.lolinsight.benchmark.application.PagedRankedPlayerDiscoveryResult
import io.github.nekke0409.lolinsight.benchmark.application.RankedPlayerDiscoveryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BenchmarkSampleValidityRepository::class)
@Testcontainers
class BenchmarkSampleValidityRepositoryIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var repository: BenchmarkSampleValidityRepository

    @Test
    fun `groups only candidate samples in the exact cohort and inclusive exclusive validity window`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            listOf(
                sample("a-top", "player-a", position = "TOP", gameStartTimestamp = WINDOW.fromInclusive),
                sample("a-middle", "player-a", position = "MIDDLE", gameStartTimestamp = WINDOW.toExclusive.minusMillis(1)),
                sample("b", "player-b", position = "JUNGLE", gameStartTimestamp = WINDOW.fromInclusive.plusSeconds(1)),
                sample("old", "player-a", gameStartTimestamp = WINDOW.fromInclusive.minusMillis(1)),
                sample("at-upper", "player-a", gameStartTimestamp = WINDOW.toExclusive),
                sample("future", "player-b", gameStartTimestamp = WINDOW.toExclusive.plusMillis(1)),
                sample("outside-candidates", "player-other", gameStartTimestamp = WINDOW.fromInclusive),
                sample("other-region", "player-a", region = "NA1", gameStartTimestamp = WINDOW.fromInclusive),
                sample("other-queue", "player-a", queueId = 440, gameStartTimestamp = WINDOW.fromInclusive),
                sample("other-tier", "player-a", tier = "PLATINUM", gameStartTimestamp = WINDOW.fromInclusive),
                sample("other-division", "player-a", division = "II", gameStartTimestamp = WINDOW.fromInclusive),
            ).map(BenchmarkSample::toEntity),
        )

        val counts =
            repository.findValidSampleCounts(
                candidatePuuids = listOf("player-b", "missing-player", "player-a"),
                region = "KR",
                queueId = 420,
                tier = "GOLD",
                division = "I",
                window = WINDOW,
            )

        assertEquals(
            mapOf(
                "missing-player" to 0L,
                "player-a" to 2L,
                "player-b" to 1L,
            ),
            counts,
        )
    }

    @Test
    fun `seed queries every discovered candidate then collects only the selected zero sample candidate`() {
        val highSamplePlayer = sampledPlayer("high-sample-player")
        val zeroSamplePlayer = sampledPlayer("zero-sample-player")
        benchmarkSampleJpaRepository.saveAndFlush(
            sample("high-sample", highSamplePlayer.puuid, gameStartTimestamp = WINDOW.fromInclusive).toEntity(),
        )
        val discoveryService = mock(RankedPlayerDiscoveryService::class.java)
        val collectionService = mock(BenchmarkMatchCollectionService::class.java)
        `when`(discoveryService.discoverPaged("GOLD", "I", 4, 2)).thenReturn(
            PagedRankedPlayerDiscoveryResult(
                players = listOf(highSamplePlayer, zeroSamplePlayer),
                discoveredPlayers = 2,
                pagesProcessed = 2,
                rateLimitStopped = false,
                retryAfterSeconds = null,
            ),
        )
        val collectionResult = collectionResult()
        `when`(collectionService.collect(listOf(zeroSamplePlayer), 5)).thenReturn(collectionResult)
        val seedService =
            BenchmarkSeedService(
                rankedPlayerDiscoveryService = discoveryService,
                benchmarkSampleValidityRepository = repository,
                benchmarkSeedCandidateSelector = BenchmarkSeedCandidateSelector(),
                benchmarkMatchCollectionService = collectionService,
                benchmarkQueryWindowFactory = mock(BenchmarkQueryWindowFactory::class.java),
            )

        val result =
            seedService.seed(
                BenchmarkSeedRequest(
                    tier = "GOLD",
                    division = "I",
                    startPage = 4,
                    pageCount = 2,
                    playerLimit = 1,
                    matchesPerPlayer = 5,
                    queryWindow = WINDOW,
                ),
            )

        assertEquals(2, result.candidatePlayers)
        assertEquals(1, result.uniquePlayers)
        assertEquals(1, result.selectedZeroValidSamplePlayers)
        assertEquals(0, result.selectedExistingValidSamplePlayers)
        assertEquals(collectionResult, result.collectionResult)
        verify(collectionService).collect(listOf(zeroSamplePlayer), 5)
    }

    private fun sample(
        matchSuffix: String,
        puuid: String,
        region: String = "KR",
        queueId: Int = 420,
        tier: String = "GOLD",
        division: String = "I",
        position: String = "TOP",
        gameStartTimestamp: Instant,
    ): BenchmarkSample =
        BenchmarkSample(
            matchId = "KR_$matchSuffix",
            puuid = puuid,
            region = region,
            queueId = queueId,
            tier = tier,
            division = division,
            rankCapturedAt = AS_OF,
            championId = 103,
            position = position,
            gameVersion = "16.18.1",
            gameStartTimestamp = gameStartTimestamp,
            kills = 1,
            deaths = 1,
            assists = 1,
            kda = 2.0,
            csPerMinute = 7.0,
            goldPerMinute = 400.0,
            damagePerMinute = 500.0,
            visionPerMinute = 1.0,
            killParticipation = 0.3,
            damageShare = 0.2,
            collectedAt = AS_OF,
        )

    private fun sampledPlayer(puuid: String): SampledRankedPlayer =
        SampledRankedPlayer(
            puuid = puuid,
            region = "KR",
            queue = "RANKED_SOLO_5x5",
            tier = "GOLD",
            division = "I",
            rankCapturedAt = AS_OF,
        )

    private fun collectionResult(): BenchmarkCollectionResult =
        BenchmarkCollectionResult(
            inputPlayers = 1,
            playersProcessed = 1,
            playerMatchListFailures = 0,
            discoveredMatchIds = 1,
            uniqueMatchIds = 1,
            fetchedMatches = 1,
            failedMatches = 0,
            createdSamples = 1,
            skippedDuplicates = 0,
            skippedInvalidSamples = 0,
            rateLimitStopped = false,
            retryAfterSeconds = null,
        )

    private companion object {
        val AS_OF: Instant = Instant.parse("2026-09-21T00:00:00Z")
        val WINDOW = BenchmarkQueryWindow(Instant.parse("2026-08-22T00:00:00Z"), AS_OF)

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
