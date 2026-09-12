package io.github.nekke0409.lolinsight.player.application

import org.springframework.stereotype.Service

@Service
class PlayerMatchStatisticsService(
    private val playerMatchHistoryLoader: PlayerMatchHistoryLoader,
    private val playerMatchStatisticsCalculator: PlayerMatchStatisticsCalculator,
) {
    fun findStatistics(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerMatchStatisticsResponse {
        val history = playerMatchHistoryLoader.loadRecentMatches(gameName, tagLine, start, count)

        return PlayerMatchStatisticsResponse(
            player = PlayerMatchStatisticsPlayerResponse(history.player.gameName, history.player.tagLine),
            sample =
                PlayerMatchStatisticsSampleResponse(
                    start = history.start,
                    requestedCount = history.requestedCount,
                    analyzedCount = history.matches.size,
                ),
            statistics = playerMatchStatisticsCalculator.calculate(history.player.puuid, history.matches),
        )
    }
}

data class PlayerMatchStatisticsResponse(
    val player: PlayerMatchStatisticsPlayerResponse,
    val sample: PlayerMatchStatisticsSampleResponse,
    val statistics: PlayerMatchStatistics,
)

data class PlayerMatchStatisticsPlayerResponse(
    val gameName: String,
    val tagLine: String,
)

data class PlayerMatchStatisticsSampleResponse(
    val start: Int,
    val requestedCount: Int,
    val analyzedCount: Int,
)
