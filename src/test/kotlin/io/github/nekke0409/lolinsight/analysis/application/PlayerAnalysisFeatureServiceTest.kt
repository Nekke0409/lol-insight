package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryLoader
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsCalculator
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistory
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class PlayerAnalysisFeatureServiceTest {
    private val playerMatchHistoryLoader = mock(PlayerMatchHistoryLoader::class.java)
    private val service =
        PlayerAnalysisFeatureService(
            playerMatchHistoryLoader,
            PlayerMatchStatisticsCalculator(),
            PlayerAnalysisFeatureBuilder(),
        )

    @Test
    fun `reuses the existing history loader and turns its sample into an analysis feature`() {
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

        val feature = service.buildFeature("Hide on bush", "KR1", 5, 20)

        verify(playerMatchHistoryLoader).loadRecentMatches("Hide on bush", "KR1", 5, 20)
        assertEquals(PlayerAnalysisFeaturePlayer("Hide on bush", "KR1"), feature.player)
        assertEquals(PlayerAnalysisFeatureSample(requestedCount = 20, analyzedCount = 0), feature.sample)
        assertEquals(0, feature.overall.games)
    }
}
