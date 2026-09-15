package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmark
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmarkResult
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.springframework.stereotype.Service

@Service
class PlayerComparisonFeatureService(
    private val playerComparisonContextService: PlayerComparisonContextService,
    private val peerBenchmarkQueryService: PeerBenchmarkQueryService,
) {
    fun buildFeature(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerComparisonFeature {
        val context = playerComparisonContextService.buildContext(gameName, tagLine, start, count)
        val comparisons = context.cohortStatistics.sortedWith(COHORT_STATISTICS_ORDER)

        return PlayerComparisonFeature(
            rankContext = context.rankContext,
            comparisons =
                context.rankContext?.let { rankContext ->
                    comparisons.toRankedComparisons(rankContext, context.targetPuuid)
                } ?: comparisons.map { statistics -> statistics.toUnrankedComparison() },
        )
    }

    private fun List<PlayerCohortStatistics>.toRankedComparisons(
        rankContext: PlayerRankContext,
        targetPuuid: String,
    ): List<PlayerCohortComparison> {
        val cohortsByStatistics = associateWith { statistics -> statistics.toBenchmarkCohort(rankContext) }
        val benchmarkResultsByCohort =
            cohortsByStatistics.values
                .distinct()
                .associateWith { cohort ->
                    peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, targetPuuid)
                }

        return map { statistics ->
            val cohort = checkNotNull(cohortsByStatistics[statistics])
            statistics.toComparison(cohort, checkNotNull(benchmarkResultsByCohort[cohort]))
        }
    }

    private fun PlayerCohortStatistics.toUnrankedComparison(): PlayerCohortComparison =
        PlayerCohortComparison(
            championId = championId,
            position = position,
            userGames = games,
            status = PlayerCohortComparisonStatus.UNRANKED,
            benchmarkCohort = null,
            benchmarkSampleCount = 0,
            benchmarkUniquePlayerCount = 0,
            metrics = null,
        )

    private fun PlayerCohortStatistics.toBenchmarkCohort(rankContext: PlayerRankContext): BenchmarkCohort =
        BenchmarkCohort(
            region = KR_REGION,
            queueId = RANKED_SOLO_QUEUE_ID,
            tier = rankContext.tier,
            division = rankContext.division,
            position = position,
            championId = championId,
        )

    private fun PlayerCohortStatistics.toComparison(
        cohort: BenchmarkCohort,
        benchmarkResult: PeerBenchmarkResult,
    ): PlayerCohortComparison {
        val status = comparisonStatus(benchmarkResult.status)

        return PlayerCohortComparison(
            championId = championId,
            position = position,
            userGames = games,
            status = status,
            benchmarkCohort = cohort,
            benchmarkSampleCount = benchmarkResult.sampleCount,
            benchmarkUniquePlayerCount = benchmarkResult.uniquePlayerCount,
            metrics =
                benchmarkResult.benchmark
                    ?.takeIf { status == PlayerCohortComparisonStatus.AVAILABLE }
                    ?.toMetrics(this),
        )
    }

    private fun PlayerCohortStatistics.comparisonStatus(benchmarkAvailability: BenchmarkAvailability): PlayerCohortComparisonStatus {
        if (games < MINIMUM_USER_GAMES_FOR_COMPARISON) {
            return PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE
        }

        return when (benchmarkAvailability) {
            BenchmarkAvailability.NO_DATA -> PlayerCohortComparisonStatus.BENCHMARK_NO_DATA
            BenchmarkAvailability.INSUFFICIENT_SAMPLE -> PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE
            BenchmarkAvailability.AVAILABLE -> PlayerCohortComparisonStatus.AVAILABLE
        }
    }

    private fun PeerBenchmark.toMetrics(statistics: PlayerCohortStatistics): PlayerComparisonMetrics =
        PlayerComparisonMetrics(
            kda = kda.toMetricComparison(playerValue = statistics.averageKda),
            csPerMinute = csPerMinute.toMetricComparison(playerValue = statistics.averageCsPerMinute),
            goldPerMinute = goldPerMinute.toMetricComparison(playerValue = statistics.averageGoldPerMinute),
            damagePerMinute = damagePerMinute.toMetricComparison(playerValue = statistics.averageDamagePerMinute),
            visionPerMinute = visionPerMinute.toMetricComparison(playerValue = statistics.averageVisionPerMinute),
            killParticipation = killParticipation.toMetricComparison(playerValue = statistics.averageKillParticipation),
            damageShare = damageShare.toMetricComparison(playerValue = statistics.averageDamageShare),
        )

    private fun BenchmarkMetricDistribution.toMetricComparison(playerValue: Double): MetricComparison =
        MetricComparison(
            playerValue = playerValue,
            benchmarkMean = mean,
            benchmarkMedian = median,
            differenceFromMean = playerValue - mean,
            differenceFromMedian = playerValue - median,
            benchmarkP25 = p25,
            benchmarkP75 = p75,
            benchmarkP90 = p90,
        )

    private companion object {
        const val KR_REGION = "KR"
        const val RANKED_SOLO_QUEUE_ID = 420
        const val MINIMUM_USER_GAMES_FOR_COMPARISON = 5

        val COHORT_STATISTICS_ORDER =
            compareByDescending<PlayerCohortStatistics> { it.games }
                .thenBy { it.position }
                .thenBy { it.championId }
    }
}
