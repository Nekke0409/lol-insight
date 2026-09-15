package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in, live verification of the player-comparison path. It deliberately does not invoke
 * PlayerAnalysisService, so it cannot make an OpenAI request.
 */
@EnabledIfEnvironmentVariable(named = "RUN_PLAYER_COMPARISON_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "RIOT_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_GAME_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_TAG_LINE", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlayerComparisonManualSmokeTest {
    @Autowired
    private lateinit var playerComparisonContextService: PlayerComparisonContextService

    @Autowired
    private lateinit var peerBenchmarkQueryService: PeerBenchmarkQueryService

    @Autowired
    private lateinit var playerComparisonFeatureService: PlayerComparisonFeatureService

    @Autowired
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate

    @Test
    fun `reports the live self-excluded middle comparison without invoking OpenAI`() {
        try {
            reportLiveComparison()
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                println("Riot 429: yes; Retry-After seconds=${exception.retryAfterSeconds ?: "not supplied"}")
                return
            }

            throw exception
        }
    }

    private fun reportLiveComparison() {
        val gameName = requiredEnvironmentValue("TARGET_GAME_NAME")
        val tagLine = requiredEnvironmentValue("TARGET_TAG_LINE")
        val context = playerComparisonContextService.buildContext(gameName, tagLine, START, COUNT)
        val middleStatistics = context.positionStatistics.singleOrNull { it.position == MIDDLE_POSITION }
        val targetCorpusPresence = findTargetCorpusPresence(context.targetPuuid)

        println("Player comparison smoke: window=start=$START, count=$COUNT")
        println("Riot authentication: succeeded")
        println(
            "Target current Solo rank: " +
                (context.rankContext?.let { "${it.tier} ${it.division}" } ?: "UNRANKED"),
        )
        println("Target MIDDLE games: ${middleStatistics?.games ?: 0}")
        println("Target in benchmark corpus: ${targetCorpusPresence.exists}")
        println("Target MIDDLE sample count: ${targetCorpusPresence.middleSampleCount}")

        val rankContext = context.rankContext
        if (rankContext?.tier != TARGET_TIER || rankContext.division != TARGET_DIVISION) {
            println("Candidate eligibility: unsuitable (expected $TARGET_TIER $TARGET_DIVISION)")
            println("Production code change: no")
            return
        }

        val middleGames = middleStatistics?.games ?: 0
        if (middleGames < MINIMUM_USER_GAMES_FOR_COMPARISON) {
            println(
                "Candidate eligibility: unsuitable " +
                    "(MIDDLE requires at least $MINIMUM_USER_GAMES_FOR_COMPARISON games)",
            )
            println("Production code change: no")
            return
        }

        val middleCohort =
            BenchmarkCohort.position(
                region = REGION,
                queueId = RANKED_SOLO_QUEUE_ID,
                tier = rankContext.tier,
                division = rankContext.division,
                position = MIDDLE_POSITION,
            )
        val completeBenchmark = peerBenchmarkQueryService.findBenchmark(middleCohort)
        val excludedBenchmark = peerBenchmarkQueryService.findBenchmarkExcludingPlayer(middleCohort, context.targetPuuid)
        val targetCohortMiddleSampleCount = findTargetCohortMiddleSampleCount(context.targetPuuid, middleCohort)

        println(
            "All MIDDLE benchmark: samples=${completeBenchmark.sampleCount}, " +
                "uniquePlayers=${completeBenchmark.uniquePlayerCount}, availability=${completeBenchmark.status}",
        )
        println(
            "Target-excluded MIDDLE benchmark: samples=${excludedBenchmark.sampleCount}, " +
                "uniquePlayers=${excludedBenchmark.uniquePlayerCount}, availability=${excludedBenchmark.status}",
        )
        println("Target exact-cohort MIDDLE sample count: $targetCohortMiddleSampleCount")

        assertEquals(completeBenchmark.sampleCount - targetCohortMiddleSampleCount, excludedBenchmark.sampleCount)
        assertEquals(
            completeBenchmark.uniquePlayerCount - if (targetCohortMiddleSampleCount > 0) 1 else 0,
            excludedBenchmark.uniquePlayerCount,
        )

        if (excludedBenchmark.status != BenchmarkAvailability.AVAILABLE) {
            println("Candidate eligibility: unsuitable (self-excluded MIDDLE benchmark is not AVAILABLE)")
            println("Production code change: no")
            return
        }

        // The production feature service intentionally reloads the same bounded window itself.
        val feature = playerComparisonFeatureService.buildFeature(gameName, tagLine, START, COUNT)
        val middlePositionComparison =
            feature.comparisons.single { comparison ->
                comparison.scope == BenchmarkScope.POSITION && comparison.position == MIDDLE_POSITION
            }

        assertEquals(BenchmarkScope.POSITION, middlePositionComparison.scope)
        assertEquals(MIDDLE_POSITION, middlePositionComparison.position)
        assertEquals(null, middlePositionComparison.championId)
        assertEquals(PlayerCohortComparisonStatus.AVAILABLE, middlePositionComparison.status)
        assertTrue(middlePositionComparison.userGames >= MINIMUM_USER_GAMES_FOR_COMPARISON)
        assertEquals(excludedBenchmark.sampleCount, middlePositionComparison.benchmarkSampleCount)
        assertEquals(excludedBenchmark.uniquePlayerCount, middlePositionComparison.benchmarkUniquePlayerCount)

        val metrics = assertNotNull(middlePositionComparison.metrics)
        assertTrue(
            listOf(
                metrics.kda,
                metrics.csPerMinute,
                metrics.goldPerMinute,
                metrics.damagePerMinute,
                metrics.visionPerMinute,
                metrics.killParticipation,
                metrics.damageShare,
            ).flatMap { metric ->
                listOf(
                    metric.playerValue,
                    metric.benchmarkMean,
                    metric.benchmarkMedian,
                    metric.differenceFromMean,
                    metric.differenceFromMedian,
                    metric.benchmarkP25,
                    metric.benchmarkP75,
                    metric.benchmarkP90,
                )
            }.all(Double::isFinite),
        )

        val ahriMiddleComparison =
            feature.comparisons.singleOrNull { comparison ->
                comparison.scope == BenchmarkScope.CHAMPION_POSITION &&
                    comparison.position == MIDDLE_POSITION &&
                    comparison.championId == AHRI_CHAMPION_ID
            }

        ahriMiddleComparison?.let { comparison ->
            assertEquals(PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE, comparison.status)
        }

        println("PlayerComparisonFeature MIDDLE/POSITION status: ${middlePositionComparison.status}")
        println("POSITION MetricComparison fields are finite: yes")
        println("Ahri/MIDDLE CHAMPION_POSITION status: ${ahriMiddleComparison?.status ?: "NOT_IN_ANALYSIS_WINDOW"}")
        println(
            "Analysis gate (an AVAILABLE comparison exists): " +
                feature.comparisons.any { it.status == PlayerCohortComparisonStatus.AVAILABLE },
        )
        println("Riot 429 or other Riot error: no")
        println("Production code change: no")
        println("OpenAI /analysis E2E: not invoked; eligible for the separate stage")
    }

    private fun findTargetCorpusPresence(targetPuuid: String): TargetCorpusPresence {
        val parameters = MapSqlParameterSource("targetPuuid", targetPuuid).addValue("middlePosition", MIDDLE_POSITION)
        val totalSampleCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM benchmark_sample WHERE puuid = :targetPuuid",
                    parameters,
                    Long::class.java,
                ),
            )
        val middleSampleCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM benchmark_sample WHERE puuid = :targetPuuid AND position = :middlePosition",
                    parameters,
                    Long::class.java,
                ),
            )

        return TargetCorpusPresence(exists = totalSampleCount > 0, middleSampleCount = middleSampleCount)
    }

    private fun findTargetCohortMiddleSampleCount(
        targetPuuid: String,
        cohort: BenchmarkCohort,
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
                """.trimIndent(),
                MapSqlParameterSource(
                    mapOf(
                        "targetPuuid" to targetPuuid,
                        "region" to cohort.region,
                        "queueId" to cohort.queueId,
                        "tier" to cohort.tier,
                        "division" to cohort.division,
                        "position" to cohort.position,
                    ),
                ),
                Long::class.java,
            ),
        )

    private fun requiredEnvironmentValue(name: String): String = checkNotNull(System.getenv(name)) { "$name must be set" }

    private data class TargetCorpusPresence(
        val exists: Boolean,
        val middleSampleCount: Long,
    )

    private companion object {
        const val START = 0
        const val COUNT = 20
        const val REGION = "KR"
        const val RANKED_SOLO_QUEUE_ID = 420
        const val TARGET_TIER = "GOLD"
        const val TARGET_DIVISION = "I"
        const val MIDDLE_POSITION = "MIDDLE"
        const val AHRI_CHAMPION_ID = 103
        const val MINIMUM_USER_GAMES_FOR_COMPARISON = 5
    }
}
