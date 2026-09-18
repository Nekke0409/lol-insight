package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import org.springframework.stereotype.Service

@Service
class BenchmarkSeedService(
    private val rankedPlayerDiscoveryService: RankedPlayerDiscoveryService,
    private val benchmarkMatchCollectionService: BenchmarkMatchCollectionService,
) {
    fun seed(request: BenchmarkSeedRequest): BenchmarkSeedResult {
        val discoveryResult =
            rankedPlayerDiscoveryService.discoverPaged(
                tier = request.tier,
                division = request.division,
                startPage = request.startPage,
                pageCount = request.pageCount,
                playerLimit = request.playerLimit,
            )
        val uniquePlayers = discoveryResult.players.distinctBy(SampledRankedPlayer::puuid)

        if (discoveryResult.rateLimitStopped) {
            return BenchmarkSeedResult(
                requestedStartPage = request.startPage,
                requestedPageCount = request.pageCount,
                discoveredPlayers = discoveryResult.discoveredPlayers,
                uniquePlayers = uniquePlayers.size,
                pagesProcessed = discoveryResult.pagesProcessed,
                collectionResult = null,
                rateLimitStopped = true,
                retryAfterSeconds = discoveryResult.retryAfterSeconds,
                emptyPageEncountered = discoveryResult.emptyPageEncountered,
            )
        }

        val collectionResult =
            benchmarkMatchCollectionService.collect(
                players = uniquePlayers,
                matchesPerPlayer = request.matchesPerPlayer,
            )

        return BenchmarkSeedResult(
            requestedStartPage = request.startPage,
            requestedPageCount = request.pageCount,
            discoveredPlayers = discoveryResult.discoveredPlayers,
            uniquePlayers = uniquePlayers.size,
            pagesProcessed = discoveryResult.pagesProcessed,
            collectionResult = collectionResult,
            rateLimitStopped = collectionResult.rateLimitStopped,
            retryAfterSeconds = collectionResult.retryAfterSeconds,
            emptyPageEncountered = discoveryResult.emptyPageEncountered,
        )
    }
}
