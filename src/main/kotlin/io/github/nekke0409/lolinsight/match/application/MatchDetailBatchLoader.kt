package io.github.nekke0409.lolinsight.match.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future

@Component
class MatchDetailBatchLoader(
    private val riotMatchClient: RiotMatchClient,
    @Qualifier(MATCH_DETAIL_EXECUTOR)
    private val matchDetailExecutor: Executor,
) {
    fun load(
        matchIds: List<String>,
        stopSchedulingWhen: (MatchDetailLoadFailure) -> Boolean = { true },
    ): List<MatchDetailLoadResult> {
        val completionService = ExecutorCompletionService<IndexedMatchDetailLoadResult>(matchDetailExecutor)
        val results = arrayOfNulls<MatchDetailLoadResult>(matchIds.size)
        val inFlight = mutableSetOf<Future<IndexedMatchDetailLoadResult>>()
        var nextMatchIndex = 0

        try {
            repeat(minOf(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS, matchIds.size)) {
                inFlight += submitMatchDetail(completionService, matchIds, nextMatchIndex)
                nextMatchIndex += 1
            }

            while (inFlight.isNotEmpty()) {
                val completedFuture = completionService.take()
                inFlight.remove(completedFuture)

                val detailResult = completedFuture.get()
                results[detailResult.index] = detailResult.result

                if (detailResult.result is MatchDetailLoadFailure && stopSchedulingWhen(detailResult.result)) {
                    cancelPendingDetails(inFlight)
                    break
                }

                if (nextMatchIndex < matchIds.size) {
                    inFlight += submitMatchDetail(completionService, matchIds, nextMatchIndex)
                    nextMatchIndex += 1
                }
            }
        } catch (exception: InterruptedException) {
            cancelPendingDetails(inFlight)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while retrieving Match details.", exception)
        }

        return results.filterNotNull()
    }

    private fun submitMatchDetail(
        completionService: ExecutorCompletionService<IndexedMatchDetailLoadResult>,
        matchIds: List<String>,
        index: Int,
    ): Future<IndexedMatchDetailLoadResult> =
        completionService.submit {
            val matchId = matchIds[index]
            val result =
                try {
                    MatchDetailLoadSuccess(matchId, riotMatchClient.findMatchById(matchId))
                } catch (_: MatchNotFoundException) {
                    MatchDetailLoadUnavailable(matchId)
                } catch (exception: RuntimeException) {
                    MatchDetailLoadFailure(matchId, exception)
                }

            IndexedMatchDetailLoadResult(index, result)
        }

    private fun cancelPendingDetails(inFlight: Collection<Future<IndexedMatchDetailLoadResult>>) {
        inFlight.forEach { it.cancel(false) }
    }

    private data class IndexedMatchDetailLoadResult(
        val index: Int,
        val result: MatchDetailLoadResult,
    )
}

sealed interface MatchDetailLoadResult {
    val matchId: String
}

data class MatchDetailLoadSuccess(
    override val matchId: String,
    val match: Match,
) : MatchDetailLoadResult

data class MatchDetailLoadUnavailable(
    override val matchId: String,
) : MatchDetailLoadResult

data class MatchDetailLoadFailure(
    override val matchId: String,
    val exception: RuntimeException,
) : MatchDetailLoadResult
