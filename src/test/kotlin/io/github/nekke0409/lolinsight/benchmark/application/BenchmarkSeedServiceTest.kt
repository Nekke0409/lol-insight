package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleValidityRepository
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkSeedServiceTest {
    private val rankedPlayerDiscoveryService = mock(RankedPlayerDiscoveryService::class.java)
    private val benchmarkSampleValidityRepository = mock(BenchmarkSampleValidityRepository::class.java)
    private val benchmarkMatchCollectionService = mock(BenchmarkMatchCollectionService::class.java)
    private val benchmarkQueryWindowFactory = mock(BenchmarkQueryWindowFactory::class.java)
    private val service =
        BenchmarkSeedService(
            rankedPlayerDiscoveryService,
            benchmarkSampleValidityRepository,
            BenchmarkSeedCandidateSelector(),
            benchmarkMatchCollectionService,
            benchmarkQueryWindowFactory,
        )

    @Test
    fun `uses the requested bounded page range deduplicates players and reuses the collector`() {
        val playerOne = player("player-one")
        val playerTwo = player("player-two")
        `when`(
            rankedPlayerDiscoveryService.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 2,
                pageCount = 2,
            ),
        ).thenReturn(
            PagedRankedPlayerDiscoveryResult(
                players = listOf(playerOne, playerOne, playerTwo),
                discoveredPlayers = 3,
                pagesProcessed = 2,
                rateLimitStopped = false,
                retryAfterSeconds = null,
            ),
        )
        val collectionResult = collectionResult(createdSamples = 4, skippedDuplicates = 1, skippedInvalidSamples = 2)
        `when`(
            benchmarkSampleValidityRepository.findValidSampleCounts(
                listOf("player-one", "player-two"),
                "KR",
                RankedSoloQueue.ID,
                "GOLD",
                "I",
                WINDOW,
            ),
        ).thenReturn(mapOf("player-one" to 4, "player-two" to 0))
        `when`(benchmarkMatchCollectionService.collect(listOf(playerTwo, playerOne), 5)).thenReturn(collectionResult)

        val result = service.seed(request(startPage = 2, pageCount = 2, playerLimit = 20, matchesPerPlayer = 5))

        assertEquals(2, result.requestedStartPage)
        assertEquals(2, result.requestedPageCount)
        assertEquals(3, result.discoveredPlayers)
        assertEquals(2, result.candidatePlayers)
        assertEquals(2, result.uniquePlayers)
        assertEquals(1, result.selectedZeroValidSamplePlayers)
        assertEquals(1, result.selectedExistingValidSamplePlayers)
        assertEquals(2, result.pagesProcessed)
        assertEquals(collectionResult, result.collectionResult)
        assertEquals(4, result.createdSamples)
        assertEquals(1, result.skippedDuplicates)
        assertEquals(2, result.skippedInvalidSamples)
        verify(rankedPlayerDiscoveryService)
            .discoverPaged(tier = "GOLD", division = "I", startPage = 2, pageCount = 2)
        verify(benchmarkSampleValidityRepository).findValidSampleCounts(
            listOf("player-one", "player-two"),
            "KR",
            RankedSoloQueue.ID,
            "GOLD",
            "I",
            WINDOW,
        )
        verify(benchmarkMatchCollectionService).collect(listOf(playerTwo, playerOne), 5)
    }

    @Test
    fun `does not start collection when paged discovery is rate limited`() {
        `when`(
            rankedPlayerDiscoveryService.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 3,
                pageCount = 1,
            ),
        ).thenReturn(
            PagedRankedPlayerDiscoveryResult(
                players = listOf(player("player-one")),
                discoveredPlayers = 1,
                pagesProcessed = 0,
                rateLimitStopped = true,
                retryAfterSeconds = 9,
            ),
        )

        val result = service.seed(request(startPage = 3, pageCount = 1, playerLimit = 10, matchesPerPlayer = 5))

        assertTrue(result.rateLimitStopped)
        assertEquals(9, result.retryAfterSeconds)
        assertNull(result.collectionResult)
        assertEquals(0, result.createdSamples)
        assertEquals(1, result.candidatePlayers)
        assertEquals(0, result.uniquePlayers)
        verifyNoInteractions(benchmarkSampleValidityRepository)
        verify(
            benchmarkMatchCollectionService,
            never(),
        ).collect(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun `propagates the collector rate limit result without changing its partial totals`() {
        val player = player("player-one")
        `when`(
            rankedPlayerDiscoveryService.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 1,
                pageCount = 1,
            ),
        ).thenReturn(
            PagedRankedPlayerDiscoveryResult(
                players = listOf(player),
                discoveredPlayers = 1,
                pagesProcessed = 1,
                rateLimitStopped = false,
                retryAfterSeconds = null,
            ),
        )
        `when`(
            benchmarkSampleValidityRepository.findValidSampleCounts(
                listOf("player-one"),
                "KR",
                RankedSoloQueue.ID,
                "GOLD",
                "I",
                WINDOW,
            ),
        ).thenReturn(mapOf("player-one" to 0))
        val collectionResult = collectionResult(createdSamples = 2, rateLimitStopped = true, retryAfterSeconds = 4)
        `when`(benchmarkMatchCollectionService.collect(listOf(player), 5)).thenReturn(collectionResult)

        val result = service.seed(request())

        assertTrue(result.rateLimitStopped)
        assertEquals(4, result.retryAfterSeconds)
        assertEquals(2, result.createdSamples)
        assertEquals(collectionResult, result.collectionResult)
    }

    private fun request(
        startPage: Int = 1,
        pageCount: Int = 1,
        playerLimit: Int = 10,
        matchesPerPlayer: Int = 5,
    ): BenchmarkSeedRequest =
        BenchmarkSeedRequest(
            tier = "GOLD",
            division = "I",
            startPage = startPage,
            pageCount = pageCount,
            playerLimit = playerLimit,
            matchesPerPlayer = matchesPerPlayer,
            queryWindow = WINDOW,
        )

    private fun player(puuid: String): SampledRankedPlayer =
        SampledRankedPlayer(
            puuid = puuid,
            region = "KR",
            queue = "RANKED_SOLO_5x5",
            tier = "GOLD",
            division = "I",
            rankCapturedAt = Instant.parse("2026-09-13T12:34:56Z"),
        )

    private fun collectionResult(
        createdSamples: Int = 0,
        skippedDuplicates: Int = 0,
        skippedInvalidSamples: Int = 0,
        rateLimitStopped: Boolean = false,
        retryAfterSeconds: Long? = null,
    ): BenchmarkCollectionResult =
        BenchmarkCollectionResult(
            inputPlayers = 2,
            playersProcessed = 2,
            playerMatchListFailures = 1,
            discoveredMatchIds = 4,
            uniqueMatchIds = 3,
            fetchedMatches = 2,
            failedMatches = 1,
            createdSamples = createdSamples,
            skippedDuplicates = skippedDuplicates,
            skippedInvalidSamples = skippedInvalidSamples,
            rateLimitStopped = rateLimitStopped,
            retryAfterSeconds = retryAfterSeconds,
        )

    private companion object {
        val WINDOW =
            BenchmarkQueryWindow(
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-09-13T00:00:00Z"),
            )
    }
}
