package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryLoader
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsCalculator
import org.springframework.stereotype.Service

@Service
class PlayerAnalysisFeatureService(
    private val playerMatchHistoryLoader: PlayerMatchHistoryLoader,
    private val playerMatchStatisticsCalculator: PlayerMatchStatisticsCalculator,
    private val playerAnalysisFeatureBuilder: PlayerAnalysisFeatureBuilder,
) {
    fun buildFeature(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerAnalysisFeature {
        val history = playerMatchHistoryLoader.loadRecentMatches(gameName, tagLine, start, count)
        val overall = playerMatchStatisticsCalculator.calculate(history.player.puuid, history.matches)

        return playerAnalysisFeatureBuilder.build(
            player = history.player,
            requestedCount = history.requestedCount,
            targetPuuid = history.player.puuid,
            matches = history.matches,
            overall = overall,
        )
    }
}
