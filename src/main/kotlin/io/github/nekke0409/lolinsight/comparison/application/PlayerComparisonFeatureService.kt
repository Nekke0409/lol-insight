package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
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
        val statistics = context.toScopedStatistics().sortedWith(SCOPED_STATISTICS_ORDER)

        return PlayerComparisonFeature(
            rankContext = context.rankContext,
            comparisons =
                context.rankContext?.let { rankContext ->
                    statistics.toRankedComparisons(rankContext, context.targetPuuid)
                } ?: statistics.map { statistics -> statistics.toUnrankedComparison() },
        )
    }

    private fun PlayerComparisonContext.toScopedStatistics(): List<ScopedPlayerStatistics> =
        positionStatistics.map(ScopedPlayerStatistics::from) +
            championPositionStatistics.map(ScopedPlayerStatistics::from)

    private fun List<ScopedPlayerStatistics>.toRankedComparisons(
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

    private fun ScopedPlayerStatistics.toUnrankedComparison(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = scope,
            position = position,
            championId = championId,
            userGames = games,
            status = PlayerCohortComparisonStatus.UNRANKED,
            benchmarkCohort = null,
            benchmarkSampleCount = 0,
            benchmarkUniquePlayerCount = 0,
            metrics = null,
        )

    private fun ScopedPlayerStatistics.toBenchmarkCohort(rankContext: PlayerRankContext): BenchmarkCohort =
        when (scope) {
            BenchmarkScope.POSITION ->
                BenchmarkCohort.position(
                    region = KR_REGION,
                    queueId = RANKED_SOLO_QUEUE_ID,
                    tier = rankContext.tier,
                    division = rankContext.division,
                    position = position,
                )

            BenchmarkScope.CHAMPION_POSITION ->
                BenchmarkCohort.championPosition(
                    region = KR_REGION,
                    queueId = RANKED_SOLO_QUEUE_ID,
                    tier = rankContext.tier,
                    division = rankContext.division,
                    position = position,
                    championId = checkNotNull(championId),
                )
        }

    private fun ScopedPlayerStatistics.toComparison(
        cohort: BenchmarkCohort,
        benchmarkResult: PeerBenchmarkResult,
    ): PlayerCohortComparison {
        val status = comparisonStatus(benchmarkResult.status)

        return PlayerCohortComparison(
            scope = scope,
            position = position,
            championId = championId,
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

    private fun ScopedPlayerStatistics.comparisonStatus(benchmarkAvailability: BenchmarkAvailability): PlayerCohortComparisonStatus {
        if (games < MINIMUM_USER_GAMES_FOR_COMPARISON) {
            return PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE
        }

        return when (benchmarkAvailability) {
            BenchmarkAvailability.NO_DATA -> PlayerCohortComparisonStatus.BENCHMARK_NO_DATA
            BenchmarkAvailability.INSUFFICIENT_SAMPLE -> PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE
            BenchmarkAvailability.AVAILABLE -> PlayerCohortComparisonStatus.AVAILABLE
        }
    }

    private fun PeerBenchmark.toMetrics(statistics: ScopedPlayerStatistics): PlayerComparisonMetrics =
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

    private data class ScopedPlayerStatistics(
        val scope: BenchmarkScope,
        val championId: Int?,
        override val position: String,
        override val games: Int,
        override val wins: Int,
        override val winRate: Double,
        override val averageKda: Double,
        override val averageCsPerMinute: Double,
        override val averageGoldPerMinute: Double,
        override val averageDamagePerMinute: Double,
        override val averageVisionPerMinute: Double,
        override val averageKillParticipation: Double,
        override val averageDamageShare: Double,
    ) : PlayerScopeStatistics {
        companion object {
            fun from(statistics: PlayerPositionStatistics): ScopedPlayerStatistics = from(BenchmarkScope.POSITION, null, statistics)

            fun from(statistics: PlayerChampionPositionStatistics): ScopedPlayerStatistics =
                from(BenchmarkScope.CHAMPION_POSITION, statistics.championId, statistics)

            private fun from(
                scope: BenchmarkScope,
                championId: Int?,
                statistics: PlayerScopeStatistics,
            ): ScopedPlayerStatistics =
                ScopedPlayerStatistics(
                    scope = scope,
                    championId = championId,
                    position = statistics.position,
                    games = statistics.games,
                    wins = statistics.wins,
                    winRate = statistics.winRate,
                    averageKda = statistics.averageKda,
                    averageCsPerMinute = statistics.averageCsPerMinute,
                    averageGoldPerMinute = statistics.averageGoldPerMinute,
                    averageDamagePerMinute = statistics.averageDamagePerMinute,
                    averageVisionPerMinute = statistics.averageVisionPerMinute,
                    averageKillParticipation = statistics.averageKillParticipation,
                    averageDamageShare = statistics.averageDamageShare,
                )
        }
    }

    private companion object {
        const val KR_REGION = "KR"
        const val RANKED_SOLO_QUEUE_ID = 420
        const val MINIMUM_USER_GAMES_FOR_COMPARISON = 5

        val SCOPED_STATISTICS_ORDER =
            compareBy<ScopedPlayerStatistics> { it.scope }
                .thenByDescending { it.games }
                .thenBy { it.position }
                .thenBy { it.championId ?: 0 }
    }
}
