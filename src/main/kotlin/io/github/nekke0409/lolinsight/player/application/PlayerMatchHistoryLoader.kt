package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future

@Service
class PlayerMatchHistoryLoader(
    private val riotAccountClient: RiotAccountClient,
    private val riotMatchClient: RiotMatchClient,
    @Qualifier(RECENT_MATCH_DETAIL_EXECUTOR)
    private val matchDetailExecutor: Executor,
) {
    fun loadRecentMatches(
        gameName: String,
        tagLine: String,
        start: Int,
        count: Int,
    ): PlayerRecentMatchHistory {
        require(start >= 0) { "start must be greater than or equal to zero." }
        require(count in MIN_COUNT..MAX_COUNT) { "count must be between $MIN_COUNT and $MAX_COUNT." }

        val account = riotAccountClient.findByRiotId(gameName, tagLine)
        val matchIds = riotMatchClient.findMatchIdsByPuuid(account.puuid, start, count)
        val detailResults = retrieveMatchDetails(matchIds, account.puuid)
        val matches = detailResults.mapNotNull { it.match }
        val unavailableCount = detailResults.count { it.match == null }

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
    ): List<IndexedMatch> {
        val completionService = ExecutorCompletionService<IndexedMatch>(matchDetailExecutor)
        val detailResults = arrayOfNulls<IndexedMatch>(matchIds.size)
        val inFlight = mutableSetOf<Future<IndexedMatch>>()
        var nextMatchIndex = 0

        try {
            repeat(minOf(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS, matchIds.size)) {
                inFlight += submitMatchDetail(completionService, matchIds, targetPuuid, nextMatchIndex)
                nextMatchIndex += 1
            }

            while (inFlight.isNotEmpty()) {
                val completedFuture = completionService.take()
                inFlight.remove(completedFuture)

                val detailResult = completedFuture.get()
                detailResults[detailResult.index] = detailResult

                if (nextMatchIndex < matchIds.size) {
                    inFlight += submitMatchDetail(completionService, matchIds, targetPuuid, nextMatchIndex)
                    nextMatchIndex += 1
                }
            }
        } catch (exception: InterruptedException) {
            cancelPendingDetails(inFlight)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while retrieving Match details.", exception)
        } catch (exception: ExecutionException) {
            cancelPendingDetails(inFlight)
            throw exception.cause ?: exception
        }

        return detailResults.map(::checkNotNull)
    }

    private fun submitMatchDetail(
        completionService: ExecutorCompletionService<IndexedMatch>,
        matchIds: List<String>,
        targetPuuid: String,
        index: Int,
    ): Future<IndexedMatch> =
        completionService.submit {
            val match =
                try {
                    riotMatchClient.findMatchById(matchIds[index])
                } catch (_: MatchNotFoundException) {
                    return@submit IndexedMatch(index, null)
                }

            IndexedMatch(
                index = index,
                match = match.takeIf { candidate -> candidate.participants.any { it.puuid == targetPuuid } },
            )
        }

    private fun cancelPendingDetails(inFlight: Collection<Future<IndexedMatch>>) {
        inFlight.forEach { it.cancel(false) }
    }

    private data class IndexedMatch(
        val index: Int,
        val match: Match?,
    )

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
