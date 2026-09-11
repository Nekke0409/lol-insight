package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future

@Service
class PlayerMatchHistoryService(
    private val riotAccountClient: RiotAccountClient,
    private val riotMatchClient: RiotMatchClient,
    @Qualifier(RECENT_MATCH_DETAIL_EXECUTOR)
    private val matchDetailExecutor: Executor,
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
        val detailResults = retrieveMatchDetails(matchIds, account.puuid)
        val summaries = detailResults.mapNotNull { it.summary }
        val unavailableCount = detailResults.count { it.summary == null }

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

    private fun retrieveMatchDetails(
        matchIds: List<String>,
        targetPuuid: String,
    ): List<IndexedMatchSummary> {
        val completionService = ExecutorCompletionService<IndexedMatchSummary>(matchDetailExecutor)
        val detailResults = arrayOfNulls<IndexedMatchSummary>(matchIds.size)
        val inFlight = mutableSetOf<Future<IndexedMatchSummary>>()
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
        completionService: ExecutorCompletionService<IndexedMatchSummary>,
        matchIds: List<String>,
        targetPuuid: String,
        index: Int,
    ): Future<IndexedMatchSummary> =
        completionService.submit {
            val matchId = matchIds[index]
            val match =
                try {
                    riotMatchClient.findMatchById(matchId)
                } catch (_: MatchNotFoundException) {
                    return@submit IndexedMatchSummary(index, null)
                }
            val participant = match.participants.firstOrNull { it.puuid == targetPuuid }

            IndexedMatchSummary(
                index = index,
                summary = participant?.let { MatchSummaryResponse.from(match, it) },
            )
        }

    private fun cancelPendingDetails(inFlight: Collection<Future<IndexedMatchSummary>>) {
        inFlight.forEach { it.cancel(false) }
    }

    private data class IndexedMatchSummary(
        val index: Int,
        val summary: MatchSummaryResponse?,
    )

    private companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 20
    }
}
