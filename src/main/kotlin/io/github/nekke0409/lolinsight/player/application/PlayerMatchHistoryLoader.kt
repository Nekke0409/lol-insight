package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.application.MatchDetailBatchLoader
import io.github.nekke0409.lolinsight.match.application.MatchDetailLoadFailure
import io.github.nekke0409.lolinsight.match.application.MatchDetailLoadSuccess
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.Executor

@Service
class PlayerMatchHistoryLoader
    @Autowired
    constructor(
        private val riotAccountClient: RiotAccountClient,
        private val riotMatchClient: RiotMatchClient,
        private val matchDetailBatchLoader: MatchDetailBatchLoader,
    ) {
        constructor(
            riotAccountClient: RiotAccountClient,
            riotMatchClient: RiotMatchClient,
            matchDetailExecutor: Executor,
        ) : this(
            riotAccountClient = riotAccountClient,
            riotMatchClient = riotMatchClient,
            matchDetailBatchLoader = MatchDetailBatchLoader(riotMatchClient, matchDetailExecutor),
        )

        fun loadRecentMatches(
            gameName: String,
            tagLine: String,
            start: Int,
            count: Int,
        ): PlayerRecentMatchHistory =
            loadMatches(
                gameName = gameName,
                tagLine = tagLine,
                start = start,
                count = count,
                queue = null,
            )

        /**
         * Loads one page from the player's Ranked Solo Match ID list for comparison analysis.
         *
         * `start` and `count` are applied by Match-V5 after the Ranked Solo queue filter, rather
         * than selecting a page of all matches and filtering it locally.
         */
        fun loadRecentRankedSoloMatches(
            gameName: String,
            tagLine: String,
            start: Int,
            count: Int,
        ): PlayerRecentMatchHistory =
            loadMatches(
                gameName = gameName,
                tagLine = tagLine,
                start = start,
                count = count,
                queue = RankedSoloQueue.ID,
            )

        private fun loadMatches(
            gameName: String,
            tagLine: String,
            start: Int,
            count: Int,
            queue: Int?,
        ): PlayerRecentMatchHistory {
            require(start >= 0) { "start must be greater than or equal to zero." }
            require(count in MIN_COUNT..MAX_COUNT) { "count must be between $MIN_COUNT and $MAX_COUNT." }

            val account = riotAccountClient.findByRiotId(gameName, tagLine)
            val matchIds = riotMatchClient.findMatchIdsByPuuid(account.puuid, start, count, queue)
            val detailResults = retrieveMatchDetails(matchIds, account.puuid)
            val matches = detailResults.filterNotNull()
            val unavailableCount = detailResults.count { it == null }

            return PlayerRecentMatchHistory(
                player = PlayerRecentMatchHistoryPlayer(account.puuid, account.gameName, account.tagLine),
                start = start,
                requestedCount = count,
                sourceMatchCount = matchIds.size,
                unavailableCount = unavailableCount,
                matches = matches,
            )
        }

        private fun retrieveMatchDetails(
            matchIds: List<String>,
            targetPuuid: String,
        ): List<Match?> =
            matchDetailBatchLoader
                .load(matchIds)
                .also { results ->
                    results.filterIsInstance<MatchDetailLoadFailure>().firstOrNull()?.let { throw it.exception }
                }.map { result ->
                    (result as? MatchDetailLoadSuccess)
                        ?.match
                        ?.takeIf { match -> match.participants.any { it.puuid == targetPuuid } }
                }

        private companion object {
            const val MIN_COUNT = 1
            const val MAX_COUNT = 20
        }
    }

data class PlayerRecentMatchHistory(
    val player: PlayerRecentMatchHistoryPlayer,
    val start: Int,
    val requestedCount: Int,
    val sourceMatchCount: Int,
    val unavailableCount: Int,
    val matches: List<Match>,
)

data class PlayerRecentMatchHistoryPlayer(
    val puuid: String,
    val gameName: String,
    val tagLine: String,
)
