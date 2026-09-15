package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARK_SEED", matches = "true")
@EnabledIfEnvironmentVariable(named = "RIOT_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkSeedManualSmokeTest {
    @Autowired
    private lateinit var benchmarkSeedService: BenchmarkSeedService

    @Autowired
    private lateinit var benchmarkCohortCoverageQueryService: BenchmarkCohortCoverageQueryService

    @Test
    fun `collects a bounded development seed and reports scoped cohort coverage`() {
        val request = requestFromEnvironment()
        val result = benchmarkSeedService.seed(request)
        val coverage =
            benchmarkCohortCoverageQueryService.findCoverage(
                BenchmarkCohortCoverageScope(
                    region = "KR",
                    queueId = 420,
                    tier = request.tier,
                    division = request.division,
                ),
            )

        println(
            "Benchmark seed: tier=${request.tier}, division=${request.division}, " +
                "pages=${request.startPage}-${request.startPage + request.pageCount - 1}, " +
                "uniquePlayers=${result.uniquePlayers}, createdSamples=${result.createdSamples}, " +
                "skippedDuplicates=${result.skippedDuplicates}, skippedInvalidSamples=${result.skippedInvalidSamples}, " +
                "rateLimitStopped=${result.rateLimitStopped}, retryAfterSeconds=${result.retryAfterSeconds}",
        )
        coverage.take(MAX_REPORTED_COHORTS).forEach { cohortCoverage ->
            println(
                "Coverage: position=${cohortCoverage.cohort.position}, championId=${cohortCoverage.cohort.championId}, " +
                    "sampleCount=${cohortCoverage.sampleCount}, uniquePlayerCount=${cohortCoverage.uniquePlayerCount}, " +
                    "availability=${cohortCoverage.availability}, samplesNeeded=${cohortCoverage.samplesNeeded}, " +
                    "uniquePlayersNeeded=${cohortCoverage.uniquePlayersNeeded}",
            )
        }
        println("Available coverage cohort present: ${coverage.any { it.availability == BenchmarkAvailability.AVAILABLE }}")
    }

    private fun requestFromEnvironment(): BenchmarkSeedRequest =
        BenchmarkSeedRequest(
            tier = environmentValue("BENCHMARK_SEED_TIER", "GOLD"),
            division = environmentValue("BENCHMARK_SEED_DIVISION", "I"),
            startPage = positiveIntEnvironmentValue("BENCHMARK_SEED_START_PAGE", 1),
            pageCount = positiveIntEnvironmentValue("BENCHMARK_SEED_PAGE_COUNT", 1),
            playerLimit = positiveIntEnvironmentValue("BENCHMARK_SEED_PLAYER_LIMIT", 10),
            matchesPerPlayer = positiveIntEnvironmentValue("BENCHMARK_SEED_MATCHES_PER_PLAYER", 5),
        )

    private fun environmentValue(
        name: String,
        defaultValue: String,
    ): String = System.getenv(name) ?: defaultValue

    private fun positiveIntEnvironmentValue(
        name: String,
        defaultValue: Int,
    ): Int {
        val value = System.getenv(name) ?: return defaultValue

        return requireNotNull(value.toIntOrNull()) { "$name must be an integer" }
    }

    private companion object {
        const val MAX_REPORTED_COHORTS = 5
    }
}
