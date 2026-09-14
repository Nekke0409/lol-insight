package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityConfiguration
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityPolicy
import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    BenchmarkAvailabilityConfiguration::class,
    BenchmarkAvailabilityPolicy::class,
    BenchmarkSampleAggregateRepository::class,
    PeerBenchmarkQueryService::class,
    PlayerComparisonFeatureService::class,
    PlayerComparisonFeatureServiceIntegrationTest.ContextServiceStubConfiguration::class,
)
@Testcontainers
class PlayerComparisonFeatureServiceIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var playerComparisonContextService: PlayerComparisonContextService

    @Autowired
    private lateinit var playerComparisonFeatureService: PlayerComparisonFeatureService

    @Test
    fun `creates an available feature from the PostgreSQL benchmark aggregate`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            (1..30)
                .map { index -> metricSample(index) }
                .map(BenchmarkSample::toEntity),
        )
        `when`(playerComparisonContextService.buildContext("Hide on bush", "KR1", 0, 20))
            .thenReturn(
                PlayerComparisonContext(
                    player = PlayerComparisonContextPlayer("Hide on bush", "KR1"),
                    rankContext = RANK_CONTEXT,
                    sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 5),
                    cohortStatistics =
                        listOf(
                            PlayerCohortStatistics(
                                championId = COHORT.championId,
                                position = COHORT.position,
                                games = 5,
                                wins = 3,
                                winRate = 0.6,
                                averageKda = 7.2,
                                averageCsPerMinute = 72.0,
                                averageGoldPerMinute = 720.0,
                                averageDamagePerMinute = 7_200.0,
                                averageVisionPerMinute = 0.72,
                                averageKillParticipation = 0.072,
                                averageDamageShare = 0.0072,
                            ),
                        ),
                ),
            )

        val feature = playerComparisonFeatureService.buildFeature("Hide on bush", "KR1", 0, 20)

        val comparison = feature.comparisons.single()
        assertEquals(PlayerCohortComparisonStatus.AVAILABLE, comparison.status)
        assertEquals(COHORT, comparison.benchmarkCohort)
        assertEquals(30L, comparison.benchmarkSampleCount)
        assertEquals(10L, comparison.benchmarkUniquePlayerCount)
        val metrics = assertNotNull(comparison.metrics)
        assertEquals(7.2, metrics.kda.playerValue, TOLERANCE)
        assertEquals(15.5, metrics.kda.benchmarkMean, TOLERANCE)
        assertEquals(15.5, metrics.kda.benchmarkMedian, TOLERANCE)
        assertEquals(-8.3, metrics.kda.differenceFromMean, TOLERANCE)
        assertEquals(-8.3, metrics.kda.differenceFromMedian, TOLERANCE)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ContextServiceStubConfiguration {
        @Bean
        fun playerComparisonContextService(): PlayerComparisonContextService = mock(PlayerComparisonContextService::class.java)
    }

    private fun metricSample(index: Int): BenchmarkSample {
        val metricValue = index.toDouble()
        return BenchmarkSample(
            matchId = "KR_available-$index",
            puuid = "available-player-${index % 10}",
            region = COHORT.region,
            queueId = COHORT.queueId,
            tier = COHORT.tier,
            division = COHORT.division,
            rankCapturedAt = RANK_CAPTURED_AT,
            championId = COHORT.championId,
            position = COHORT.position,
            gameVersion = "16.18.1",
            gameStartTimestamp = GAME_STARTED_AT,
            kills = index,
            deaths = 1,
            assists = index,
            kda = metricValue,
            csPerMinute = metricValue * 10.0,
            goldPerMinute = metricValue * 100.0,
            damagePerMinute = metricValue * 1_000.0,
            visionPerMinute = metricValue * 0.1,
            killParticipation = metricValue * 0.01,
            damageShare = metricValue * 0.001,
            collectedAt = COLLECTED_AT,
        )
    }

    private companion object {
        val COHORT =
            BenchmarkCohort(
                region = "KR",
                queueId = 420,
                tier = "GOLD",
                division = "I",
                position = "MIDDLE",
                championId = 103,
            )
        val RANK_CONTEXT = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))
        val RANK_CAPTURED_AT: Instant = Instant.parse("2026-09-13T10:15:30Z")
        val GAME_STARTED_AT: Instant = Instant.parse("2026-09-13T09:00:00Z")
        val COLLECTED_AT: Instant = Instant.parse("2026-09-13T10:16:00Z")
        const val TOLERANCE = 0.000001

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
