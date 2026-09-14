package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import org.springframework.stereotype.Service

@Service
class PlayerAnalysisService(
    private val playerComparisonFeatureService: PlayerComparisonFeatureService,
    private val playerAnalysisInputMapper: PlayerAnalysisInputMapper,
    private val playerAnalysisGenerator: PlayerAnalysisGenerator,
) {
    fun analyze(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerAnalysisResponse {
        val feature = playerComparisonFeatureService.buildFeature(gameName, tagLine, start, count)

        if (feature.rankContext == null) {
            return PlayerAnalysisResponse(PlayerAnalysisResponseStatus.UNRANKED, null)
        }
        if (feature.comparisons.none { it.status == PlayerCohortComparisonStatus.AVAILABLE }) {
            return PlayerAnalysisResponse(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, null)
        }

        val analysis = playerAnalysisGenerator.generate(playerAnalysisInputMapper.map(feature))
        return PlayerAnalysisResponse(PlayerAnalysisResponseStatus.ANALYZED, analysis)
    }
}
