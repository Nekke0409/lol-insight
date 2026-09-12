package io.github.nekke0409.lolinsight.player.application

import org.springframework.stereotype.Service

@Service
class PlayerMatchHistoryService(
    private val playerMatchHistoryLoader: PlayerMatchHistoryLoader,
) {
    fun findRecentMatches(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): RecentMatchesResponse {
        val history = playerMatchHistoryLoader.loadRecentMatches(gameName, tagLine, start, count)
        val summaries =
            history.matches.map { match ->
                val participant = checkNotNull(match.participants.firstOrNull { it.puuid == history.player.puuid })
                MatchSummaryResponse.from(match, participant)
            }

        return RecentMatchesResponse(
            player = RecentMatchesPlayerResponse(history.player.gameName, history.player.tagLine),
            page =
                RecentMatchesPageResponse(
                    start = history.start,
                    requestedCount = history.requestedCount,
                    sourceMatchCount = history.sourceMatchCount,
                    returnedCount = summaries.size,
                    partial = history.unavailableCount > 0,
                    unavailableCount = history.unavailableCount,
                ),
            matches = summaries,
        )
    }
}
