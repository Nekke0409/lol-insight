package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.comparison.application.MetricComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeature
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
import org.springframework.stereotype.Component

@Component
class PlayerAnalysisInputMapper {
    fun map(feature: PlayerComparisonFeature): PlayerAnalysisInput {
        val comparisons =
            feature.comparisons
                .filter { it.status == PlayerCohortComparisonStatus.AVAILABLE }
                .map(::toAnalysisComparison)

        return PlayerAnalysisInput(
            comparisons = comparisons,
            analysisLimitations = ANALYSIS_LIMITATIONS.sortedBy(PlayerAnalysisLimitation::code),
        )
    }

    private fun toAnalysisComparison(comparison: PlayerCohortComparison): AnalysisComparisonInput {
        val cohort = checkNotNull(comparison.benchmarkCohort)
        val metrics = checkNotNull(comparison.metrics)

        return AnalysisComparisonInput(
            scope = comparison.scope,
            position = comparison.position,
            championId = comparison.championId,
            userGames = comparison.userGames,
            benchmarkCohort =
                PlayerAnalysisBenchmarkCohort(
                    region = cohort.region,
                    queueId = cohort.queueId,
                    tier = cohort.tier,
                    division = cohort.division,
                    position = cohort.position,
                    championId = cohort.championId,
                ),
            benchmarkSampleCount = comparison.benchmarkSampleCount,
            benchmarkUniquePlayerCount = comparison.benchmarkUniquePlayerCount,
            metrics = metrics.toAnalysisMetrics(),
        )
    }

    private fun PlayerComparisonMetrics.toAnalysisMetrics(): List<PlayerAnalysisMetric> =
        listOf(
            kda.toAnalysisMetric(PlayerAnalysisMetricName.KDA),
            csPerMinute.toAnalysisMetric(PlayerAnalysisMetricName.CS_PER_MINUTE),
            goldPerMinute.toAnalysisMetric(PlayerAnalysisMetricName.GOLD_PER_MINUTE),
            damagePerMinute.toAnalysisMetric(PlayerAnalysisMetricName.DAMAGE_PER_MINUTE),
            visionPerMinute.toAnalysisMetric(PlayerAnalysisMetricName.VISION_PER_MINUTE),
            killParticipation.toAnalysisMetric(PlayerAnalysisMetricName.KILL_PARTICIPATION),
            damageShare.toAnalysisMetric(PlayerAnalysisMetricName.DAMAGE_SHARE),
        )

    private fun MetricComparison.toAnalysisMetric(name: PlayerAnalysisMetricName): PlayerAnalysisMetric =
        PlayerAnalysisMetric(
            name = name,
            playerValue = playerValue,
            benchmarkMean = benchmarkMean,
            benchmarkMedian = benchmarkMedian,
            differenceFromMean = differenceFromMean,
            differenceFromMedian = differenceFromMedian,
            benchmarkP25 = benchmarkP25,
            benchmarkP75 = benchmarkP75,
            benchmarkP90 = benchmarkP90,
        )

    private companion object {
        val ANALYSIS_LIMITATIONS =
            listOf(
                PlayerAnalysisLimitation(
                    code = "MATCH_LEVEL_BENCHMARK",
                    description = "Benchmark v0.1은 peer player의 player-level 집계가 아니라 경기 단위 관측치 분포입니다.",
                ),
                PlayerAnalysisLimitation(
                    code = "PATCH_FRESHNESS",
                    description = "Benchmark v0.1에는 여러 gameVersion의 경기 표본이 섞여 있을 수 있습니다.",
                ),
                PlayerAnalysisLimitation(
                    code = "BACKEND_AVAILABILITY_GATE",
                    description = "표본 충분성 및 비교 가능 여부는 Backend 정책이 이미 판단했습니다.",
                ),
            )
    }
}
