package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkCohortCoverageRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import org.junit.jupiter.api.Test
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

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
    BenchmarkQueryWindowFactory::class,
    BenchmarkCohortCoverageRepository::class,
    BenchmarkSampleAggregateRepository::class,
    BenchmarkCohortCoverageQueryService::class,
    BenchmarkCohortCoverageQueryServiceIntegrationTest.FixedClockConfiguration::class,
)
@Testcontainers
class BenchmarkCohortCoverageQueryServiceIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var benchmarkSampleAggregateRepository: BenchmarkSampleAggregateRepository

    @Autowired
    private lateinit var benchmarkCohortCoverageQueryService: BenchmarkCohortCoverageQueryService

    @Test
    fun `reports both position and champion-position coverage from PostgreSQL`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            (
                samples("available", 30, 10, TARGET_COHORT) +
                    samples("near", 25, 9, TARGET_COHORT.copy(championId = 55)) +
                    samples("partial-middle", 24, 8, TARGET_COHORT.copy(championId = 84)) +
                    samples("partial", 24, 8, TARGET_COHORT.copy(position = "TOP", championId = 84)) +
                    samples("other-region", 1, 1, TARGET_COHORT.copy(region = "NA1")) +
                    samples("other-queue", 1, 1, TARGET_COHORT.copy(queueId = 440)) +
                    samples("other-tier", 1, 1, TARGET_COHORT.copy(tier = "PLATINUM")) +
                    samples("other-division", 1, 1, TARGET_COHORT.copy(division = "II"))
            ).map(BenchmarkSample::toEntity),
        )

        val coverage = benchmarkCohortCoverageQueryService.findCoverage(TARGET_SCOPE)

        assertEquals(6, coverage.size)

        val positionMiddle = coverage.single { it.cohort.scope == BenchmarkScope.POSITION && it.cohort.position == "MIDDLE" }
        assertEquals(null, positionMiddle.cohort.championId)
        assertEquals(79, positionMiddle.sampleCount)
        assertEquals(27, positionMiddle.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.AVAILABLE, positionMiddle.availability)
        assertEquals(0, positionMiddle.samplesNeeded)
        assertEquals(0, positionMiddle.uniquePlayersNeeded)

        val positionTop = coverage.single { it.cohort.scope == BenchmarkScope.POSITION && it.cohort.position == "TOP" }
        assertEquals(24, positionTop.sampleCount)
        assertEquals(8, positionTop.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, positionTop.availability)
        assertEquals(6, positionTop.samplesNeeded)
        assertEquals(2, positionTop.uniquePlayersNeeded)

        val championMiddle =
            coverage.single {
                it.cohort.scope == BenchmarkScope.CHAMPION_POSITION && it.cohort.position == "MIDDLE" && it.cohort.championId == 55
            }
        assertEquals(25, championMiddle.sampleCount)
        assertEquals(9, championMiddle.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, championMiddle.availability)
        assertEquals(5, championMiddle.samplesNeeded)
        assertEquals(1, championMiddle.uniquePlayersNeeded)
    }

    @Test
    fun `uses the validity window for coverage and matches the aggregate count`() {
        benchmarkSampleJpaRepository.saveAllAndFlush(
            (
                samples("expired", 30, 10, TARGET_COHORT, WINDOW.fromInclusive.minusMillis(1)) +
                    samples("valid", 29, 10, TARGET_COHORT, WINDOW.fromInclusive) +
                    samples("future", 30, 10, TARGET_COHORT.copy(championId = 55), WINDOW.toExclusive)
            ).map(BenchmarkSample::toEntity),
        )

        val coverage = benchmarkCohortCoverageQueryService.findCoverage(TARGET_SCOPE, WINDOW)
        val championCoverage = coverage.single { it.cohort.scope == BenchmarkScope.CHAMPION_POSITION && it.cohort.championId == 103 }
        val positionCoverage = coverage.single { it.cohort.scope == BenchmarkScope.POSITION && it.cohort.position == "MIDDLE" }
        val aggregate =
            requireNotNull(
                benchmarkSampleAggregateRepository.findBenchmark(
                    BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
                    WINDOW,
                ),
            )

        assertEquals(29L, championCoverage.sampleCount)
        assertEquals(10L, championCoverage.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, championCoverage.availability)
        assertEquals(1L, championCoverage.samplesNeeded)
        assertEquals(0L, championCoverage.uniquePlayersNeeded)
        assertEquals(29L, positionCoverage.sampleCount)
        assertEquals(29L, aggregate.sampleCount)
        assertEquals(10L, aggregate.uniquePlayerCount)
        assertEquals(false, coverage.any { it.cohort.scope == BenchmarkScope.CHAMPION_POSITION && it.cohort.championId == 55 })
    }

    private fun samples(
        prefix: String,
        count: Int,
        uniquePlayerCount: Int,
        cohort: BenchmarkCohort,
        gameStartTimestamp: Instant = GAME_STARTED_AT,
    ): List<BenchmarkSample> =
        (1..count).map { index ->
            BenchmarkSample(
                matchId = "KR_$prefix-$index",
                puuid = "$prefix-player-${index % uniquePlayerCount}",
                region = cohort.region,
                queueId = cohort.queueId,
                tier = cohort.tier,
                division = cohort.division,
                rankCapturedAt = RANK_CAPTURED_AT,
                championId = checkNotNull(cohort.championId),
                position = cohort.position,
                gameVersion = "16.18.1",
                gameStartTimestamp = gameStartTimestamp,
                kills = 5,
                deaths = 2,
                assists = 7,
                kda = 6.0,
                csPerMinute = 7.5,
                goldPerMinute = 450.0,
                damagePerMinute = 700.0,
                visionPerMinute = 1.1,
                killParticipation = 0.6,
                damageShare = 0.2,
                collectedAt = COLLECTED_AT,
            )
        }

    private companion object {
        val TARGET_COHORT =
            BenchmarkCohort(
                scope = BenchmarkScope.CHAMPION_POSITION,
                region = "KR",
                queueId = 420,
                tier = "GOLD",
                division = "I",
                position = "MIDDLE",
                championId = 103,
            )
        val TARGET_SCOPE = BenchmarkCohortCoverageScope("KR", 420, "GOLD", "I")
        val RANK_CAPTURED_AT: Instant = Instant.parse("2026-09-13T10:15:30Z")
        val GAME_STARTED_AT: Instant = Instant.parse("2026-09-13T09:00:00Z")
        val COLLECTED_AT: Instant = Instant.parse("2026-09-13T10:16:00Z")
        val AS_OF: Instant = Instant.parse("2026-09-14T00:00:00Z")
        val WINDOW = BenchmarkQueryWindow(AS_OF.minus(Duration.ofDays(30)), AS_OF)

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

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.fixed(AS_OF, ZoneOffset.UTC)
    }
}
