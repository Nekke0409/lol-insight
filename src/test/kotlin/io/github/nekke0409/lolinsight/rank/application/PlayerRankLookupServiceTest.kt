package io.github.nekke0409.lolinsight.rank.application

import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PlayerRankLookupServiceTest {
    private val riotLeagueClient = mock(RiotLeagueClient::class.java)
    private val capturedAt = Instant.parse("2026-09-14T01:23:45Z")
    private val service =
        PlayerRankLookupService(
            riotLeagueClient = riotLeagueClient,
            clock = Clock.fixed(capturedAt, ZoneOffset.UTC),
        )

    @Test
    fun `maps the current Ranked Solo rank with its capture time`() {
        `when`(riotLeagueClient.findCurrentRankedSoloRank("player-puuid"))
            .thenReturn(CurrentRankedSoloRank(tier = "GOLD", division = "I"))

        val context = service.findCurrentRankContext("player-puuid")

        assertEquals(PlayerRankContext(tier = "GOLD", division = "I", capturedAt = capturedAt), context)
        verify(riotLeagueClient).findCurrentRankedSoloRank("player-puuid")
    }

    @Test
    fun `returns an unavailable context for an unranked player`() {
        `when`(riotLeagueClient.findCurrentRankedSoloRank("player-puuid")).thenReturn(null)

        val context = service.findCurrentRankContext("player-puuid")

        assertEquals(null, context)
        verify(riotLeagueClient).findCurrentRankedSoloRank("player-puuid")
    }

    @Test
    fun `propagates a provider failure`() {
        val exception = RiotApiResponseException(HttpStatus.BAD_GATEWAY, "provider failure")
        `when`(riotLeagueClient.findCurrentRankedSoloRank("player-puuid")).thenThrow(exception)

        val thrown =
            assertFailsWith<RiotApiResponseException> {
                service.findCurrentRankContext("player-puuid")
            }

        assertSame(exception, thrown)
        verify(riotLeagueClient).findCurrentRankedSoloRank("player-puuid")
    }
}
