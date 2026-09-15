package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import org.junit.jupiter.api.Test
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
)
@Testcontainers
class PeerBenchmarkQueryServiceIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var benchmarkSampleAggregateRepository: BenchmarkSampleAggregateRepository

    @Autowired
    private lateinit var peerBenchmarkQueryService: PeerBenchmarkQueryService

    @Test
    fun `PostgreSQL aggregate calculates match observation distributions and isolates each cohort key`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            (
                targetSamples() +
                    listOf(
                        metricSample("other-region", "other-region", TARGET_COHORT.copy(region = "NA1"), 100.0),
                        metricSample("other-queue", "other-queue", TARGET_COHORT.copy(queueId = 440), 100.0),
                        metricSample("other-tier", "other-tier", TARGET_COHORT.copy(tier = "PLATINUM"), 100.0),
                        metricSample("other-division", "other-division", TARGET_COHORT.copy(division = "II"), 100.0),
                        metricSample("other-position", "other-position", TARGET_COHORT.copy(position = "TOP"), 100.0),
                        metricSample("other-champion", "other-champion", TARGET_COHORT.copy(championId = 84), 100.0),
                    )
            ).map(BenchmarkSample::toEntity),
        )

        val benchmark = requireNotNull(benchmarkSampleAggregateRepository.findBenchmark(TARGET_COHORT))

        assertEquals(TARGET_COHORT, benchmark.cohort)
        assertEquals(4L, benchmark.sampleCount)
        assertEquals(2L, benchmark.uniquePlayerCount)
        assertDistribution(expectedDistribution(1.0), benchmark.kda)
        assertDistribution(expectedDistribution(10.0), benchmark.csPerMinute)
        assertDistribution(expectedDistribution(100.0), benchmark.goldPerMinute)
        assertDistribution(expectedDistribution(1_000.0), benchmark.damagePerMinute)
        assertDistribution(expectedDistribution(0.1), benchmark.visionPerMinute)
        assertDistribution(expectedDistribution(0.01), benchmark.killParticipation)
        assertDistribution(expectedDistribution(0.001), benchmark.damageShare)
    }

    @Test
    fun `PostgreSQL aggregate excludes the target player from every benchmark statistic`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            listOf(
                metricSample("player-a-1", "player-a", TARGET_COHORT, 100.0),
                metricSample("player-a-2", "player-a", TARGET_COHORT, 200.0),
                metricSample("player-b-1", "player-b", TARGET_COHORT, 1.0),
                metricSample("player-b-2", "player-b", TARGET_COHORT, 2.0),
                metricSample("player-c-1", "player-c", TARGET_COHORT, 3.0),
                metricSample("player-c-2", "player-c", TARGET_COHORT, 4.0),
            ).map(BenchmarkSample::toEntity),
        )

        val excludingPlayerA =
            requireNotNull(
                benchmarkSampleAggregateRepository.findBenchmarkExcludingPlayer(TARGET_COHORT, "player-a"),
            )
        val resultExcludingPlayerA = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(TARGET_COHORT, "player-a")
        val excludingPlayerB =
            requireNotNull(
                benchmarkSampleAggregateRepository.findBenchmarkExcludingPlayer(TARGET_COHORT, "player-b"),
            )

        assertEquals(4L, excludingPlayerA.sampleCount)
        assertEquals(2L, excludingPlayerA.uniquePlayerCount)
        assertDistribution(expectedDistribution(1.0), excludingPlayerA.kda)
        assertDistribution(expectedDistribution(10.0), excludingPlayerA.csPerMinute)
        assertDistribution(expectedDistribution(100.0), excludingPlayerA.goldPerMinute)
        assertDistribution(expectedDistribution(1_000.0), excludingPlayerA.damagePerMinute)
        assertDistribution(expectedDistribution(0.1), excludingPlayerA.visionPerMinute)
        assertDistribution(expectedDistribution(0.01), excludingPlayerA.killParticipation)
        assertDistribution(expectedDistribution(0.001), excludingPlayerA.damageShare)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, resultExcludingPlayerA.status)
        assertEquals(4L, resultExcludingPlayerA.sampleCount)
        assertEquals(2L, resultExcludingPlayerA.uniquePlayerCount)
        assertNull(resultExcludingPlayerA.benchmark)

        assertEquals(4L, excludingPlayerB.sampleCount)
        assertEquals(2L, excludingPlayerB.uniquePlayerCount)
        assertEquals(76.75, excludingPlayerB.kda.mean, TOLERANCE)
        assertEquals(52.0, excludingPlayerB.kda.median, TOLERANCE)
        assertEquals(3.75, excludingPlayerB.kda.p25, TOLERANCE)
        assertEquals(125.0, excludingPlayerB.kda.p75, TOLERANCE)
        assertEquals(170.0, excludingPlayerB.kda.p90, TOLERANCE)
    }

    @Test
    fun `returns no data insufficient sample and available according to the configured policy`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(targetSamples().map(BenchmarkSample::toEntity))

        val insufficient = peerBenchmarkQueryService.findBenchmark(TARGET_COHORT)

        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, insufficient.status)
        assertEquals(4L, insufficient.sampleCount)
        assertEquals(2L, insufficient.uniquePlayerCount)
        assertNull(insufficient.benchmark)

        val noData = peerBenchmarkQueryService.findBenchmark(TARGET_COHORT.copy(championId = 84))

        assertEquals(BenchmarkAvailability.NO_DATA, noData.status)
        assertEquals(0L, noData.sampleCount)
        assertEquals(0L, noData.uniquePlayerCount)
        assertNull(noData.benchmark)

        benchmarkSampleJpaRepository.saveAllAndFlush(
            (1..30)
                .map { index ->
                    metricSample(
                        matchSuffix = "available-$index",
                        puuid = "available-player-${index % 10}",
                        cohort = AVAILABLE_COHORT,
                        metricValue = index.toDouble(),
                    )
                }.map(BenchmarkSample::toEntity),
        )

        val available = peerBenchmarkQueryService.findBenchmark(AVAILABLE_COHORT)

        assertEquals(BenchmarkAvailability.AVAILABLE, available.status)
        assertEquals(30L, available.sampleCount)
        assertEquals(10L, available.uniquePlayerCount)
        val availableBenchmark = assertNotNull(available.benchmark)
        assertEquals(AVAILABLE_COHORT, availableBenchmark.cohort)
    }

    @Test
    fun `re-evaluates availability after excluding the target player`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            (
                (1..5).map { index -> metricSample("target-$index", "target-player", TARGET_COHORT, index.toDouble()) } +
                    (1..29).map { index ->
                        metricSample(
                            matchSuffix = "peer-$index",
                            puuid = "peer-player-${(index - 1) % 9}",
                            cohort = TARGET_COHORT,
                            metricValue = index.toDouble(),
                        )
                    }
            ).map(BenchmarkSample::toEntity),
        )

        val fullCorpus = peerBenchmarkQueryService.findBenchmark(TARGET_COHORT)
        val excludingTarget = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(TARGET_COHORT, "target-player")

        assertEquals(BenchmarkAvailability.AVAILABLE, fullCorpus.status)
        assertEquals(34L, fullCorpus.sampleCount)
        assertEquals(10L, fullCorpus.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, excludingTarget.status)
        assertEquals(29L, excludingTarget.sampleCount)
        assertEquals(9L, excludingTarget.uniquePlayerCount)
        assertNull(excludingTarget.benchmark)
    }

    @Test
    fun `returns no data when every matching sample belongs to the excluded player`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            listOf(
                metricSample("target-only-1", "target-player", TARGET_COHORT, 1.0),
                metricSample("target-only-2", "target-player", TARGET_COHORT, 2.0),
            ).map(BenchmarkSample::toEntity),
        )

        val result = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(TARGET_COHORT, "target-player")

        assertEquals(BenchmarkAvailability.NO_DATA, result.status)
        assertEquals(0L, result.sampleCount)
        assertEquals(0L, result.uniquePlayerCount)
        assertNull(result.benchmark)
    }

    private fun targetSamples(): List<BenchmarkSample> =
        listOf(
            metricSample("target-1", "player-a", TARGET_COHORT, 1.0),
            metricSample("target-2", "player-a", TARGET_COHORT, 2.0),
            metricSample("target-3", "player-b", TARGET_COHORT, 3.0),
            metricSample("target-4", "player-b", TARGET_COHORT, 4.0),
        )

    private fun metricSample(
        matchSuffix: String,
        puuid: String,
        cohort: BenchmarkCohort,
        metricValue: Double,
    ): BenchmarkSample =
        BenchmarkSample(
            matchId = "KR_$matchSuffix",
            puuid = puuid,
            region = cohort.region,
            queueId = cohort.queueId,
            tier = cohort.tier,
            division = cohort.division,
            rankCapturedAt = RANK_CAPTURED_AT,
            championId = cohort.championId,
            position = cohort.position,
            gameVersion = "16.18.1",
            gameStartTimestamp = GAME_STARTED_AT,
            kills = metricValue.toInt(),
            deaths = 1,
            assists = metricValue.toInt(),
            kda = metricValue,
            csPerMinute = metricValue * 10.0,
            goldPerMinute = metricValue * 100.0,
            damagePerMinute = metricValue * 1_000.0,
            visionPerMinute = metricValue * 0.1,
            killParticipation = metricValue * 0.01,
            damageShare = metricValue * 0.001,
            collectedAt = COLLECTED_AT,
        )

    private fun expectedDistribution(scale: Double): BenchmarkMetricDistribution =
        BenchmarkMetricDistribution(
            mean = 2.5 * scale,
            median = 2.5 * scale,
            p25 = 1.75 * scale,
            p75 = 3.25 * scale,
            p90 = 3.7 * scale,
        )

    private fun assertDistribution(
        expected: BenchmarkMetricDistribution,
        actual: BenchmarkMetricDistribution,
    ) {
        assertEquals(expected.mean, actual.mean, TOLERANCE)
        assertEquals(expected.median, actual.median, TOLERANCE)
        assertEquals(expected.p25, actual.p25, TOLERANCE)
        assertEquals(expected.p75, actual.p75, TOLERANCE)
        assertEquals(expected.p90, actual.p90, TOLERANCE)
    }

    private companion object {
        val TARGET_COHORT =
            BenchmarkCohort(
                region = "KR",
                queueId = 420,
                tier = "GOLD",
                division = "I",
                position = "MIDDLE",
                championId = 103,
            )
        val AVAILABLE_COHORT = TARGET_COHORT.copy(championId = 84)
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
