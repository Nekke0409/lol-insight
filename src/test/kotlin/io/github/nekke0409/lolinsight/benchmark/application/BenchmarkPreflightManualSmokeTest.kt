package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonAvailabilityPolicy
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import kotlin.test.assertEquals

/**
 * Opt-in live diagnostic for one configured Ranked Solo cohort and position.
 *
 * It does not seed samples, create jobs, or invoke OpenAI. Building the existing comparison
 * context may read Riot data and its existing cache, so this test remains disabled by default.
 * Target identifiers and PUUID are never printed.
 */
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARK_PREFLIGHT", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "analysis.automation.enabled=false",
        "analysis.automation.bootstrap.enabled=false",
        "benchmark.replenishment.enabled=false",
        "benchmark.replenishment.run-once=false",
        "agent.enabled=false",
    ],
)
class BenchmarkPreflightManualSmokeTest {
    @Autowired
    private lateinit var playerComparisonContextService: PlayerComparisonContextService

    @Autowired
    private lateinit var peerBenchmarkQueryService: PeerBenchmarkQueryService

    @Autowired
    private lateinit var playerComparisonAvailabilityPolicy: PlayerComparisonAvailabilityPolicy

    @Autowired
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate

    @Test
    fun `reports configured cohort readiness and the self-excluded benchmark without creating work`() {
        val settings = BenchmarkPreflightSettings.fromEnvironment()
        val context = playerComparisonContextService.buildContext(settings.gameName, settings.tagLine, START, COUNT)
        val userPositionGames = context.positionStatistics.singleOrNull { it.position == settings.position }?.games ?: 0
        val window = peerBenchmarkQueryService.currentWindow()
        val cohort =
            BenchmarkCohort.position(
                region = REGION,
                queueId = RankedSoloQueue.ID,
                tier = settings.expectedCohort.tier,
                division = settings.expectedCohort.division,
                position = settings.position,
            )
        val complete = peerBenchmarkQueryService.findBenchmark(cohort, window)
        val selfExcluded = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, context.targetPuuid, window)
        val targetSampleCount =
            findTargetSampleCount(
                context.targetPuuid,
                settings,
                window.fromInclusive.toEpochMilli(),
                window.toExclusive.toEpochMilli(),
            )
        val assessment =
            BenchmarkPreflightAssessment.assess(
                actualRank = context.rankContext,
                expectedCohort = settings.expectedCohort,
                userPositionGames = userPositionGames,
                selfExcludedAvailability = selfExcluded.status,
                comparisonAvailabilityPolicy = playerComparisonAvailabilityPolicy,
            )

        assertEquals(complete.sampleCount - targetSampleCount, selfExcluded.sampleCount)
        assertEquals(
            complete.uniquePlayerCount - if (targetSampleCount > 0) 1 else 0,
            selfExcluded.uniquePlayerCount,
        )

        println("Benchmark preflight: Ranked Solo window=start=$START, count=$COUNT")
        println("Expected current Solo rank: ${settings.expectedCohort.tier} ${settings.expectedCohort.division}")
        println("Actual current Solo rank: ${assessment.actualRankLabel}")
        println("Rank matches expected cohort: ${assessment.rankMatchesExpectedCohort}")
        println("Target ${settings.position} games: ${assessment.userPositionGames}")
        println("Benchmark window: from=${window.fromInclusive}, to=${window.toExclusive}")
        println(
            "All ${settings.expectedCohort.tier} ${settings.expectedCohort.division} ${settings.position}: " +
                "samples=${complete.sampleCount}, uniquePlayers=${complete.uniquePlayerCount}, availability=${complete.status}",
        )
        println(
            "Self-excluded ${settings.expectedCohort.tier} ${settings.expectedCohort.division} ${settings.position}: " +
                "samples=${selfExcluded.sampleCount}, uniquePlayers=${selfExcluded.uniquePlayerCount}, " +
                "availability=${assessment.selfExcludedAvailability}",
        )
        println("Target exact-cohort ${settings.position} samples: $targetSampleCount")
        val readiness = if (assessment.ready) "READY" else "NOT_READY"
        println("Readiness: $readiness (${assessment.reason})")
        println("Analysis job: not created")
    }

    private fun findTargetSampleCount(
        targetPuuid: String,
        settings: BenchmarkPreflightSettings,
        fromInclusiveEpochMillis: Long,
        toExclusiveEpochMillis: Long,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM benchmark_sample
                WHERE puuid = :targetPuuid
                  AND region = :region
                  AND queue_id = :queueId
                  AND tier = :tier
                  AND division = :division
                  AND position = :position
                  AND game_start_timestamp >= to_timestamp(:fromInclusiveEpochMillis / 1000.0)
                  AND game_start_timestamp < to_timestamp(:toExclusiveEpochMillis / 1000.0)
                """.trimIndent(),
                MapSqlParameterSource(
                    mapOf(
                        "targetPuuid" to targetPuuid,
                        "region" to REGION,
                        "queueId" to RankedSoloQueue.ID,
                        "tier" to settings.expectedCohort.tier,
                        "division" to settings.expectedCohort.division,
                        "position" to settings.position,
                        "fromInclusiveEpochMillis" to fromInclusiveEpochMillis,
                        "toExclusiveEpochMillis" to toExclusiveEpochMillis,
                    ),
                ),
                Long::class.java,
            ),
        )

    private companion object {
        const val START = 0
        const val COUNT = 20
        const val REGION = "KR"
    }
}
