package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMatchHistoryServiceTest {
    private val riotAccountClient = mock(RiotAccountClient::class.java)
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val matchDetailExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
    private val service = PlayerMatchHistoryService(riotAccountClient, riotMatchClient, matchDetailExecutor)

    @AfterEach
    fun shutDownMatchDetailExecutor() {
        matchDetailExecutor.shutdownNow()
    }

    @Test
    fun `retrieves Match IDs and details and summarizes the target participant`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 5, 2)).thenReturn(listOf("KR_2", "KR_1"))
        `when`(riotMatchClient.findMatchById("KR_2"))
            .thenReturn(
                match(
                    matchId = "KR_2",
                    participants =
                        listOf(
                            participant(puuid = "other-puuid", championName = "Darius", kills = 1, laneCs = 180),
                            participant(puuid = "target-puuid", championName = "Aatrox", kills = 8, laneCs = 150, neutralCs = 12),
                        ),
                ),
            )
        `when`(riotMatchClient.findMatchById("KR_1"))
            .thenReturn(
                match(
                    matchId = "KR_1",
                    participants = listOf(participant(puuid = "target-puuid", championName = "Ahri", kills = 4)),
                ),
            )

        val response = service.findRecentMatches("Hide on bush", "KR1", start = 5, count = 2)

        val calls = inOrder(riotAccountClient, riotMatchClient)
        calls.verify(riotAccountClient).findByRiotId("Hide on bush", "KR1")
        calls.verify(riotMatchClient).findMatchIdsByPuuid("target-puuid", 5, 2)
        verify(riotMatchClient).findMatchById("KR_2")
        verify(riotMatchClient).findMatchById("KR_1")

        assertEquals("Hide on bush", response.player.gameName)
        assertEquals("KR1", response.player.tagLine)
        assertEquals(5, response.page.start)
        assertEquals(2, response.page.requestedCount)
        assertEquals(2, response.page.sourceMatchCount)
        assertEquals(2, response.page.returnedCount)
        assertFalse(response.page.partial)
        assertEquals(0, response.page.unavailableCount)
        assertEquals(listOf("KR_2", "KR_1"), response.matches.map { it.matchId })

        val targetSummary = response.matches.first().participant
        assertEquals(266, targetSummary.championId)
        assertEquals("Aatrox", targetSummary.championName)
        assertEquals(8, targetSummary.kills)
        assertEquals(162, targetSummary.totalCs)
        assertEquals(listOf(3_073, 0, 3_364), targetSummary.itemIds)
    }

    @Test
    fun `preserves source Match ID order when Detail requests finish out of order`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 2)).thenReturn(listOf("KR_slow", "KR_fast"))
        val fastDetailCompleted = CountDownLatch(1)
        val releaseSlowDetail = CountDownLatch(1)
        `when`(riotMatchClient.findMatchById("KR_slow"))
            .thenAnswer {
                try {
                    assertTrue(releaseSlowDetail.await(2, TimeUnit.SECONDS))
                    match("KR_slow", listOf(participant(puuid = "target-puuid")))
                } finally {
                    releaseSlowDetail.countDown()
                }
            }
        `when`(riotMatchClient.findMatchById("KR_fast"))
            .thenAnswer {
                fastDetailCompleted.countDown()
                match("KR_fast", listOf(participant(puuid = "target-puuid")))
            }

        val responseFuture =
            CompletableFuture.supplyAsync {
                service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 2)
            }

        try {
            assertTrue(fastDetailCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseSlowDetail.countDown()
        }

        val response = responseFuture.get(2, TimeUnit.SECONDS)

        assertEquals(listOf("KR_slow", "KR_fast"), response.matches.map { it.matchId })
    }

    @Test
    fun `returns an empty non-partial response when the Player has no Match IDs`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 20)).thenReturn(emptyList())

        val response = service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 20)

        verify(riotAccountClient).findByRiotId("Hide on bush", "KR1")
        verify(riotMatchClient).findMatchIdsByPuuid("target-puuid", 0, 20)
        verifyNoMoreInteractions(riotMatchClient)
        assertEquals(emptyList(), response.matches)
        assertEquals(0, response.page.sourceMatchCount)
        assertEquals(0, response.page.returnedCount)
        assertFalse(response.page.partial)
        assertEquals(0, response.page.unavailableCount)
    }

    @Test
    fun `omits unavailable details and Match details without the target participant`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 3))
            .thenReturn(listOf("KR_404", "KR_without-target", "KR_available"))
        `when`(riotMatchClient.findMatchById("KR_404")).thenThrow(MatchNotFoundException())
        `when`(riotMatchClient.findMatchById("KR_without-target"))
            .thenReturn(match("KR_without-target", listOf(participant(puuid = "other-puuid"))))
        `when`(riotMatchClient.findMatchById("KR_available"))
            .thenReturn(match("KR_available", listOf(participant(puuid = "target-puuid"))))

        val response = service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 3)

        assertEquals(listOf("KR_available"), response.matches.map { it.matchId })
        assertTrue(response.page.partial)
        assertEquals(2, response.page.unavailableCount)
        assertEquals(3, response.page.sourceMatchCount)
        assertEquals(1, response.page.returnedCount)
    }

    @Test
    fun `propagates a Riot rate limit response from Match detail retrieval`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 1)).thenReturn(listOf("KR_1"))
        val exception = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", retryAfterSeconds = 7)
        `when`(riotMatchClient.findMatchById("KR_1")).thenThrow(exception)

        assertEquals(
            exception,
            assertFailsWith<RiotApiResponseException> {
                service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 1)
            },
        )
    }

    @Test
    fun `propagates a Riot 5xx response from Match detail retrieval`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 1)).thenReturn(listOf("KR_1"))
        val responseException = RiotApiResponseException(HttpStatus.INTERNAL_SERVER_ERROR, "upstream failure")
        `when`(riotMatchClient.findMatchById("KR_1")).thenThrow(responseException)

        assertEquals(
            responseException,
            assertFailsWith<RiotApiResponseException> {
                service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 1)
            },
        )
    }

    @Test
    fun `propagates a Riot transport failure from Match detail retrieval`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 1)).thenReturn(listOf("KR_1"))
        val exception = RiotApiTransportException(RestClientException("connect timed out"))
        `when`(riotMatchClient.findMatchById("KR_1")).thenThrow(exception)

        assertEquals(
            exception,
            assertFailsWith<RiotApiTransportException> {
                service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 1)
            },
        )
    }

    @Test
    fun `does not schedule new Detail requests after a Riot rate limit response`() {
        accountLookupReturns()
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 5))
            .thenReturn(listOf("KR_429", "KR_2", "KR_3", "KR_4", "KR_5"))
        val otherInitialDetailsStarted = CountDownLatch(3)
        val releaseOtherInitialDetails = CountDownLatch(1)
        val exception = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", retryAfterSeconds = 7)
        `when`(riotMatchClient.findMatchById(anyString()))
            .thenAnswer { invocation ->
                when (val matchId = invocation.getArgument<String>(0)) {
                    "KR_429" -> {
                        assertTrue(otherInitialDetailsStarted.await(2, TimeUnit.SECONDS))
                        throw exception
                    }

                    else -> {
                        otherInitialDetailsStarted.countDown()
                        try {
                            assertTrue(releaseOtherInitialDetails.await(2, TimeUnit.SECONDS))
                            match(matchId, listOf(participant(puuid = "target-puuid")))
                        } finally {
                            releaseOtherInitialDetails.countDown()
                        }
                    }
                }
            }

        try {
            assertEquals(
                exception,
                assertFailsWith<RiotApiResponseException> {
                    service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 5)
                },
            )
            verify(riotMatchClient, never()).findMatchById("KR_5")
        } finally {
            releaseOtherInitialDetails.countDown()
        }
    }

    @Test
    fun `does not exceed four concurrent Detail requests`() {
        accountLookupReturns()
        val matchIds = (1..8).map { "KR_$it" }
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 8)).thenReturn(matchIds)
        val activeRequests = AtomicInteger()
        val maximumActiveRequests = AtomicInteger()
        val initialDetailsStarted = CountDownLatch(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
        val releaseDetails = CountDownLatch(1)
        `when`(riotMatchClient.findMatchById(anyString()))
            .thenAnswer { invocation ->
                val activeRequestCount = activeRequests.incrementAndGet()
                maximumActiveRequests.updateAndGet { maxOf(it, activeRequestCount) }
                initialDetailsStarted.countDown()
                try {
                    assertTrue(releaseDetails.await(2, TimeUnit.SECONDS))
                    match(invocation.getArgument<String>(0), listOf(participant(puuid = "target-puuid")))
                } finally {
                    activeRequests.decrementAndGet()
                }
            }
        val widerExecutor = Executors.newFixedThreadPool(8)
        val concurrencyLimitedService = PlayerMatchHistoryService(riotAccountClient, riotMatchClient, widerExecutor)
        val responseFuture =
            CompletableFuture.supplyAsync {
                concurrencyLimitedService.findRecentMatches("Hide on bush", "KR1", start = 0, count = 8)
            }

        try {
            assertTrue(initialDetailsStarted.await(2, TimeUnit.SECONDS))
            assertEquals(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS, maximumActiveRequests.get())
        } finally {
            releaseDetails.countDown()
        }

        try {
            assertEquals(8, responseFuture.get(2, TimeUnit.SECONDS).matches.size)
            assertTrue(maximumActiveRequests.get() <= MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
        } finally {
            widerExecutor.shutdownNow()
        }
    }

    @Test
    fun `rejects pagination outside the supported range before calling Riot`() {
        assertFailsWith<IllegalArgumentException> {
            service.findRecentMatches("Hide on bush", "KR1", start = -1, count = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.findRecentMatches("Hide on bush", "KR1", start = 0, count = 21)
        }

        verifyNoInteractions(riotAccountClient, riotMatchClient)
    }

    private fun accountLookupReturns() {
        `when`(riotAccountClient.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(
                RiotAccountResponse(
                    puuid = "target-puuid",
                    gameName = "Hide on bush",
                    tagLine = "KR1",
                ),
            )
    }

    private fun match(
        matchId: String,
        participants: List<MatchParticipant>,
    ): Match {
        val objective = ObjectiveResult(wasFirst = false, killCount = 0)
        return Match(
            matchId = matchId,
            queueId = 420,
            gameMode = "CLASSIC",
            gameVersion = "16.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            endedAt = Instant.parse("2026-01-01T12:30:00Z"),
            duration = Duration.ofMinutes(30),
            participants = participants,
            teams =
                listOf(
                    MatchTeam(
                        teamId = 100,
                        won = true,
                        bannedChampionIds = emptyList(),
                        objectives =
                            MatchObjectives(
                                atakhan = objective,
                                baron = objective,
                                champion = objective,
                                dragon = objective,
                                horde = objective,
                                inhibitor = objective,
                                riftHerald = objective,
                                tower = objective,
                            ),
                    ),
                ),
        )
    }

    private fun participant(
        puuid: String,
        championName: String = "Aatrox",
        kills: Int = 8,
        laneCs: Int = 180,
        neutralCs: Int = 12,
    ): MatchParticipant =
        MatchParticipant(
            participantId = 1,
            puuid = puuid,
            riotId = RiotIdSnapshot("Player", "KR1"),
            teamId = 100,
            won = true,
            position = "TOP",
            champion = MatchChampion(266, championName, 18),
            kills = kills,
            deaths = 2,
            assists = 5,
            pentaKills = 0,
            laneMinionKills = laneCs,
            neutralMinionKills = neutralCs,
            goldEarned = 12_345,
            championDamageDealt = 24_680,
            damageTaken = 17_890,
            vision = MatchVision(score = 28, wardsPlaced = 9, wardsKilled = 3),
            turretKills = 2,
            itemIdsBySlot = listOf(3_073, 0, 3_364),
            summonerSpellIds = listOf(4, 12),
            perks = MatchPerks(5_008, 5_010, 5_011, emptyList()),
            reportedChallenges = null,
        )
}
