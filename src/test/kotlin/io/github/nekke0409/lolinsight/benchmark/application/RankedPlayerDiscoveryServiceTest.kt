package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RankedPlayerDiscoveryServiceTest {
    private val riotLeagueClient = mock(RiotLeagueClient::class.java)
    private val rankCapturedAt = Instant.parse("2026-09-13T12:34:56Z")
    private val service =
        RankedPlayerDiscoveryService(
            riotLeagueClient = riotLeagueClient,
            clock = Clock.fixed(rankCapturedAt, ZoneOffset.UTC),
        )

    @Test
    fun `creates at most playerLimit sampled players with the discovered rank context`() {
        `when`(riotLeagueClient.findRankedPlayerPuuids("GOLD", "I", 10))
            .thenReturn((1..11).map { "puuid-$it" })

        val players = service.discover(tier = "GOLD", division = "I", playerLimit = 10)

        assertEquals(10, players.size)
        assertEquals((1..10).map { "puuid-$it" }, players.map { it.puuid })
        assertEquals(setOf("KR"), players.map { it.region }.toSet())
        assertEquals(setOf("RANKED_SOLO_5x5"), players.map { it.queue }.toSet())
        assertEquals(setOf("GOLD"), players.map { it.tier }.toSet())
        assertEquals(setOf("I"), players.map { it.division }.toSet())
        assertEquals(setOf(rankCapturedAt), players.map { it.rankCapturedAt }.toSet())
        verify(riotLeagueClient).findRankedPlayerPuuids("GOLD", "I", 10)
    }

    @Test
    fun `returns an empty result when no ranked players are available`() {
        `when`(riotLeagueClient.findRankedPlayerPuuids("GOLD", "I", 10)).thenReturn(emptyList())

        val players = service.discover(tier = "GOLD", division = "I", playerLimit = 10)

        assertEquals(emptyList(), players)
        verify(riotLeagueClient).findRankedPlayerPuuids("GOLD", "I", 10)
    }

    @Test
    fun `propagates rate limiting as a terminal discovery failure`() {
        val rateLimitException = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", 3)
        `when`(riotLeagueClient.findRankedPlayerPuuids("GOLD", "I", 10)).thenThrow(rateLimitException)

        val thrown =
            assertFailsWith<RiotApiResponseException> {
                service.discover(tier = "GOLD", division = "I", playerLimit = 10)
            }

        assertSame(rateLimitException, thrown)
        verify(riotLeagueClient).findRankedPlayerPuuids("GOLD", "I", 10)
    }

    @Test
    fun `traverses the requested page range and stops at an empty page`() {
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 2)).thenReturn(listOf("puuid-2"))
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 3)).thenReturn(listOf("puuid-3"))
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 4)).thenReturn(emptyList())

        val result =
            service.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 2,
                pageCount = 4,
            )

        assertEquals(listOf("puuid-2", "puuid-3"), result.players.map { it.puuid })
        assertEquals(2, result.discoveredPlayers)
        assertEquals(2, result.uniquePlayers)
        assertEquals(3, result.pagesProcessed)
        verify(riotLeagueClient).findRankedPlayerPuuidsOnPage("GOLD", "I", 2)
        verify(riotLeagueClient).findRankedPlayerPuuidsOnPage("GOLD", "I", 3)
        verify(riotLeagueClient).findRankedPlayerPuuidsOnPage("GOLD", "I", 4)
        verify(riotLeagueClient, never()).findRankedPlayerPuuidsOnPage("GOLD", "I", 5)
    }

    @Test
    fun `deduplicates PUUIDs while traversing every requested nonempty page`() {
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 2))
            .thenReturn(listOf("puuid-2", "puuid-1"))
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 3))
            .thenReturn(listOf("puuid-1", "puuid-3", "puuid-4"))

        val result =
            service.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 2,
                pageCount = 3,
            )

        assertEquals(listOf("puuid-1", "puuid-2", "puuid-3", "puuid-4"), result.players.map { it.puuid })
        assertEquals(5, result.discoveredPlayers)
        assertEquals(4, result.uniquePlayers)
        assertEquals(3, result.pagesProcessed)
        verify(riotLeagueClient).findRankedPlayerPuuidsOnPage("GOLD", "I", 4)
    }

    @Test
    fun `stops the requested page traversal after a rate limit response`() {
        val rateLimitException = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", 7)
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 2)).thenReturn(listOf("puuid-2"))
        `when`(riotLeagueClient.findRankedPlayerPuuidsOnPage("GOLD", "I", 3)).thenThrow(rateLimitException)

        val result =
            service.discoverPaged(
                tier = "GOLD",
                division = "I",
                startPage = 2,
                pageCount = 3,
            )

        assertEquals(listOf("puuid-2"), result.players.map { it.puuid })
        assertEquals(1, result.pagesProcessed)
        assertTrue(result.rateLimitStopped)
        assertEquals(7, result.retryAfterSeconds)
        verify(riotLeagueClient, never()).findRankedPlayerPuuidsOnPage("GOLD", "I", 4)
    }
}
