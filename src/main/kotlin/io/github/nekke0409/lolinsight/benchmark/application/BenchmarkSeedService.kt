package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleValidityRepository
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import org.springframework.stereotype.Service

@Service
class BenchmarkSeedService(
    private val rankedPlayerDiscoveryService: RankedPlayerDiscoveryService,
    private val benchmarkSampleValidityRepository: BenchmarkSampleValidityRepository,
    private val benchmarkSeedCandidateSelector: BenchmarkSeedCandidateSelector,
    private val benchmarkMatchCollectionService: BenchmarkMatchCollectionService,
    private val benchmarkQueryWindowFactory: BenchmarkQueryWindowFactory,
) {
    fun seed(request: BenchmarkSeedRequest): BenchmarkSeedResult {
        val window = request.queryWindow ?: benchmarkQueryWindowFactory.current()
        val discoveryResult =
            rankedPlayerDiscoveryService.discoverPaged(
                tier = request.tier,
                division = request.division,
                startPage = request.startPage,
                pageCount = request.pageCount,
            )
        val candidatePlayers = discoveryResult.players.distinctBy(SampledRankedPlayer::puuid)

        if (discoveryResult.rateLimitStopped) {
            return BenchmarkSeedResult(
                requestedStartPage = request.startPage,
                requestedPageCount = request.pageCount,
                discoveredPlayers = discoveryResult.discoveredPlayers,
                candidatePlayers = candidatePlayers.size,
                uniquePlayers = 0,
                selectedZeroValidSamplePlayers = 0,
                selectedExistingValidSamplePlayers = 0,
                pagesProcessed = discoveryResult.pagesProcessed,
                collectionResult = null,
                rateLimitStopped = true,
                retryAfterSeconds = discoveryResult.retryAfterSeconds,
                emptyPageEncountered = discoveryResult.emptyPageEncountered,
            )
        }

        val validSampleCounts =
            benchmarkSampleValidityRepository.findValidSampleCounts(
                candidatePuuids = candidatePlayers.map(SampledRankedPlayer::puuid),
                region = KR_REGION,
                queueId = RankedSoloQueue.ID,
                tier = request.tier,
                division = request.division,
                window = window,
            )
        val selection =
            benchmarkSeedCandidateSelector.select(
                candidates = candidatePlayers,
                validSampleCounts = validSampleCounts,
                playerLimit = request.playerLimit,
            )

        val collectionResult =
            benchmarkMatchCollectionService.collect(
                players = selection.players,
                matchesPerPlayer = request.matchesPerPlayer,
            )

        return BenchmarkSeedResult(
            requestedStartPage = request.startPage,
            requestedPageCount = request.pageCount,
            discoveredPlayers = discoveryResult.discoveredPlayers,
            candidatePlayers = candidatePlayers.size,
            uniquePlayers = selection.players.size,
            selectedZeroValidSamplePlayers = selection.selectedZeroValidSamplePlayers,
            selectedExistingValidSamplePlayers = selection.selectedExistingValidSamplePlayers,
            pagesProcessed = discoveryResult.pagesProcessed,
            collectionResult = collectionResult,
            rateLimitStopped = collectionResult.rateLimitStopped,
            retryAfterSeconds = collectionResult.retryAfterSeconds,
            emptyPageEncountered = discoveryResult.emptyPageEncountered,
        )
    }

    private companion object {
        const val KR_REGION = "KR"
    }
}
