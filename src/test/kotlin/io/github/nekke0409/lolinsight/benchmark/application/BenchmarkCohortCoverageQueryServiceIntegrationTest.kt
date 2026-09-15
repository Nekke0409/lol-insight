package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkCohortCoverageRepository
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
    BenchmarkCohortCoverageRepository::class,
    BenchmarkCohortCoverageQueryService::class,
)
@Testcontainers
class BenchmarkCohortCoverageQueryServiceIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var benchmarkCohortCoverageQueryService: BenchmarkCohortCoverageQueryService

    @Test
    fun `groups exact cohorts in PostgreSQL calculates gaps and orders coverage deterministically`() {
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

        assertEquals(
            listOf(
                TARGET_COHORT,
                TARGET_COHORT.copy(championId = 55),
                TARGET_COHORT.copy(championId = 84),
                TARGET_COHORT.copy(position = "TOP", championId = 84),
            ),
            coverage.map { it.cohort },
        )

        val available = coverage[0]
        assertEquals(30, available.sampleCount)
        assertEquals(10, available.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.AVAILABLE, available.availability)
        assertEquals(0, available.samplesNeeded)
        assertEquals(0, available.uniquePlayersNeeded)

        val near = coverage[1]
        assertEquals(25, near.sampleCount)
        assertEquals(9, near.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, near.availability)
        assertEquals(5, near.samplesNeeded)
        assertEquals(1, near.uniquePlayersNeeded)

        val partial = coverage[2]
        assertEquals(24, partial.sampleCount)
        assertEquals(8, partial.uniquePlayerCount)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, partial.availability)
        assertEquals(6, partial.samplesNeeded)
        assertEquals(2, partial.uniquePlayersNeeded)
    }

    private fun samples(
        prefix: String,
        count: Int,
        uniquePlayerCount: Int,
        cohort: BenchmarkCohort,
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
                championId = cohort.championId,
                position = cohort.position,
                gameVersion = "16.18.1",
                gameStartTimestamp = GAME_STARTED_AT,
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
