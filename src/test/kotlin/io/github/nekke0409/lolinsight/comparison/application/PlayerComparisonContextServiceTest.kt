package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryLoader
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistory
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import io.github.nekke0409.lolinsight.rank.application.PlayerRankLookupService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerComparisonContextServiceTest {
    private val playerMatchHistoryLoader = mock(PlayerMatchHistoryLoader::class.java)
    private val playerRankLookupService = mock(PlayerRankLookupService::class.java)
    private val service =
        PlayerComparisonContextService(
            playerMatchHistoryLoader = playerMatchHistoryLoader,
            playerRankLookupService = playerRankLookupService,
            playerComparisonContextBuilder = PlayerComparisonContextBuilder(),
        )

    @Test
    fun `combines the existing player match sample with the current Solo rank without querying a benchmark`() {
        `when`(playerMatchHistoryLoader.loadRecentMatches("Hide on bush", "KR1", 5, 20))
            .thenReturn(
                PlayerRecentMatchHistory(
                    player = PlayerRecentMatchHistoryPlayer("target-puuid", "Hide on bush", "KR1"),
                    start = 5,
                    requestedCount = 20,
                    sourceMatchCount = 0,
                    unavailableCount = 0,
                    matches = emptyList(),
                ),
            )
        `when`(playerRankLookupService.findCurrentRankContext("target-puuid"))
            .thenReturn(PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z")))

        val context = service.buildContext("Hide on bush", "KR1", 5, 20)

        verify(playerMatchHistoryLoader).loadRecentMatches("Hide on bush", "KR1", 5, 20)
        verify(playerRankLookupService).findCurrentRankContext("target-puuid")
        assertEquals(PlayerComparisonContextPlayer("Hide on bush", "KR1"), context.player)
        assertEquals("target-puuid", context.targetPuuid)
        assertEquals(PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 0), context.sample)
        assertTrue(context.cohortStatistics.isEmpty())
    }
}
