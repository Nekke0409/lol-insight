package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import kotlin.test.assertEquals

/**
 * Opt-in live preflight for an Emerald IV TOP benchmark verification.
 *
 * It only reuses the existing comparison context and benchmark query boundaries. It never seeds
 * samples, creates an analysis job, or invokes OpenAI. Target identifiers and PUUID are not
 * printed.
 */
@EnabledIfEnvironmentVariable(named = "RUN_EMERALD_TOP_BENCHMARK_PREFLIGHT", matches = "true")
@EnabledIfEnvironmentVariable(named = "RIOT_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_GAME_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_TAG_LINE", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmeraldTopBenchmarkPreflightManualSmokeTest {
    @Autowired
    private lateinit var playerComparisonContextService: PlayerComparisonContextService

    @Autowired
    private lateinit var peerBenchmarkQueryService: PeerBenchmarkQueryService

    @Autowired
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate

    @Test
    fun `reports the target eligibility and self-excluded Emerald IV TOP benchmark without side effects`() {
        val context =
            playerComparisonContextService.buildContext(
                requiredEnvironmentValue("TARGET_GAME_NAME"),
                requiredEnvironmentValue("TARGET_TAG_LINE"),
                START,
                COUNT,
            )
        val rank = context.rankContext
        val topGames = context.positionStatistics.singleOrNull { it.position == TOP_POSITION }?.games ?: 0

        println("Emerald TOP preflight: Ranked Solo window=start=$START, count=$COUNT")
        println("Target current Solo rank: ${rank?.let { "${it.tier} ${it.division}" } ?: "UNRANKED"}")
        println("Target TOP games: $topGames")

        if (rank?.tier != TIER || rank.division != DIVISION) {
            println("Collection eligibility: unsuitable (expected $TIER $DIVISION)")
            println("Analysis job: not created")
            return
        }

        val window = peerBenchmarkQueryService.currentWindow()
        val cohort =
            BenchmarkCohort.position(
                region = REGION,
                queueId = RANKED_SOLO_QUEUE_ID,
                tier = TIER,
                division = DIVISION,
                position = TOP_POSITION,
            )
        val complete = peerBenchmarkQueryService.findBenchmark(cohort, window)
        val excluded = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, context.targetPuuid, window)
        val targetSampleCount =
            findTargetSampleCount(
                context.targetPuuid,
                window.fromInclusive.toEpochMilli(),
                window.toExclusive.toEpochMilli(),
            )

        assertEquals(complete.sampleCount - targetSampleCount, excluded.sampleCount)
        assertEquals(
            complete.uniquePlayerCount - if (targetSampleCount > 0) 1 else 0,
            excluded.uniquePlayerCount,
        )

        println("Benchmark window: from=${window.fromInclusive}, to=${window.toExclusive}")
        println(
            "All Emerald IV TOP: samples=${complete.sampleCount}, " +
                "uniquePlayers=${complete.uniquePlayerCount}, availability=${complete.status}",
        )
        println(
            "Self-excluded Emerald IV TOP: samples=${excluded.sampleCount}, " +
                "uniquePlayers=${excluded.uniquePlayerCount}, availability=${excluded.status}",
        )
        println("Target exact-cohort TOP samples: $targetSampleCount")
        println("Analysis job: not created")
    }

    private fun findTargetSampleCount(
        targetPuuid: String,
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
                        "queueId" to RANKED_SOLO_QUEUE_ID,
                        "tier" to TIER,
                        "division" to DIVISION,
                        "position" to TOP_POSITION,
                        "fromInclusiveEpochMillis" to fromInclusiveEpochMillis,
                        "toExclusiveEpochMillis" to toExclusiveEpochMillis,
                    ),
                ),
                Long::class.java,
            ),
        )

    private fun requiredEnvironmentValue(name: String): String = checkNotNull(System.getenv(name)) { "$name must be set" }

    private companion object {
        const val START = 0
        const val COUNT = 20
        const val REGION = "KR"
        const val RANKED_SOLO_QUEUE_ID = 420
        const val TIER = "EMERALD"
        const val DIVISION = "IV"
        const val TOP_POSITION = "TOP"
    }
}
