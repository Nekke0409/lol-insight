package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryLoader
import io.github.nekke0409.lolinsight.rank.application.PlayerRankLookupService
import org.springframework.stereotype.Service

@Service
class PlayerComparisonContextService(
    private val playerMatchHistoryLoader: PlayerMatchHistoryLoader,
    private val playerRankLookupService: PlayerRankLookupService,
    private val playerComparisonContextBuilder: PlayerComparisonContextBuilder,
) {
    fun buildContext(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerComparisonContext {
        val history = playerMatchHistoryLoader.loadRecentMatches(gameName, tagLine, start, count)
        val rankContext = playerRankLookupService.findCurrentRankContext(history.player.puuid)

        return playerComparisonContextBuilder.build(
            player = history.player,
            requestedCount = history.requestedCount,
            targetPuuid = history.player.puuid,
            matches = history.matches,
            rankContext = rankContext,
        )
    }
}
