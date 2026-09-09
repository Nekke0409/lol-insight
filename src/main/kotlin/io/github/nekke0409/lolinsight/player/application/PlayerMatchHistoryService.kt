package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import org.springframework.stereotype.Service

@Service
class PlayerMatchHistoryService(
    private val riotAccountClient: RiotAccountClient,
    private val riotMatchClient: RiotMatchClient,
) {
    fun findRecentMatches(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): RecentMatchesResponse {
        require(start >= 0) { "start must be greater than or equal to zero." }
        require(count in MIN_COUNT..MAX_COUNT) { "count must be between $MIN_COUNT and $MAX_COUNT." }

        val account = riotAccountClient.findByRiotId(gameName, tagLine)
        val matchIds = riotMatchClient.findMatchIdsByPuuid(account.puuid, start, count)
        val summaries = mutableListOf<MatchSummaryResponse>()
        var unavailableCount = 0

        for (matchId in matchIds) {
            val match =
                try {
                    riotMatchClient.findMatchById(matchId)
                } catch (_: MatchNotFoundException) {
                    unavailableCount += 1
                    continue
                }
            val participant = match.participants.firstOrNull { it.puuid == account.puuid }

            if (participant == null) {
                unavailableCount += 1
                continue
            }

            summaries.add(MatchSummaryResponse.from(match, participant))
        }

        return RecentMatchesResponse(
            player = RecentMatchesPlayerResponse(account.gameName, account.tagLine),
            page =
                RecentMatchesPageResponse(
                    start = start,
                    requestedCount = count,
                    sourceMatchCount = matchIds.size,
                    returnedCount = summaries.size,
                    partial = unavailableCount > 0,
                    unavailableCount = unavailableCount,
                ),
            matches = summaries,
        )
    }

    private companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 20
    }
}
