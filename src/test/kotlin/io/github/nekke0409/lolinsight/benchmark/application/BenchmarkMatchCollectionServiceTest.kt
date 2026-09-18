package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldownException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.match.application.MatchDetailBatchLoader
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkMatchCollectionServiceTest {
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val benchmarkSamplePersistenceService = mock(BenchmarkSamplePersistenceService::class.java)
    private val matchDetailExecutor = Executors.newFixedThreadPool(4)
    private val collectedAt = Instant.parse("2026-09-14T00:00:00Z")
    private val service =
        BenchmarkMatchCollectionService(
            riotMatchClient = riotMatchClient,
            matchDetailBatchLoader = MatchDetailBatchLoader(riotMatchClient, matchDetailExecutor),
            benchmarkSamplePersistenceService = benchmarkSamplePersistenceService,
            clock = Clock.fixed(collectedAt, ZoneOffset.UTC),
        )

    @AfterEach
    fun shutDownMatchDetailExecutor() {
        matchDetailExecutor.shutdownNow()
    }

    @Test
    fun `deduplicates Match Details while preserving each sampled Player rank context`() {
        val playerA = sampledPlayer("player-a", tier = "GOLD", division = "I")
        val playerB = sampledPlayer("player-b", tier = "PLATINUM", division = "IV")
        `when`(riotMatchClient.findMatchIdsByPuuid("player-a", 0, 3, 420)).thenReturn(listOf("M1", "M2"))
        `when`(riotMatchClient.findMatchIdsByPuuid("player-b", 0, 3, 420)).thenReturn(listOf("M1", "M3"))
        `when`(riotMatchClient.findMatchById("M1"))
            .thenReturn(
                match(
                    "M1",
                    participant("player-a", teamId = 100, position = "TOP", kills = 8),
                    participant("player-b", teamId = 200, position = "MIDDLE", kills = 6),
                    participant("unsampled-player", teamId = 100, position = "JUNGLE", kills = 2),
                ),
            )
        `when`(riotMatchClient.findMatchById("M2"))
            .thenReturn(match("M2", participant("player-a", teamId = 100, position = "TOP", kills = 4)))
        `when`(riotMatchClient.findMatchById("M3"))
            .thenReturn(match("M3", participant("player-b", teamId = 200, position = "MIDDLE", kills = 3)))
        `when`(benchmarkSamplePersistenceService.saveIfAbsent(anyBenchmarkSample()))
            .thenReturn(
                BenchmarkSampleSaveResult.INSERTED,
                BenchmarkSampleSaveResult.INSERTED,
                BenchmarkSampleSaveResult.INSERTED,
                BenchmarkSampleSaveResult.INSERTED,
                BenchmarkSampleSaveResult.ALREADY_EXISTS,
                BenchmarkSampleSaveResult.ALREADY_EXISTS,
                BenchmarkSampleSaveResult.ALREADY_EXISTS,
                BenchmarkSampleSaveResult.ALREADY_EXISTS,
            )

        val first = service.collect(listOf(playerA, playerB), matchesPerPlayer = 3)
        val second = service.collect(listOf(playerA, playerB), matchesPerPlayer = 3)

        assertEquals(
            BenchmarkCollectionResult(
                inputPlayers = 2,
                playersProcessed = 2,
                playerMatchListFailures = 0,
                discoveredMatchIds = 4,
                uniqueMatchIds = 3,
                fetchedMatches = 3,
                failedMatches = 0,
                createdSamples = 4,
                skippedDuplicates = 0,
                skippedInvalidSamples = 0,
                rateLimitStopped = false,
                retryAfterSeconds = null,
            ),
            first,
        )
        assertEquals(0, second.createdSamples)
        assertEquals(4, second.skippedDuplicates)
        verify(riotMatchClient, times(2)).findMatchById("M1")
        verify(riotMatchClient, times(2)).findMatchById("M2")
        verify(riotMatchClient, times(2)).findMatchById("M3")

        val samples = ArgumentCaptor.forClass(BenchmarkSample::class.java)
        verify(benchmarkSamplePersistenceService, times(8)).saveIfAbsent(capture(samples))
        val firstCollectionSamples = samples.allValues.take(4)
        assertEquals(
            setOf("M1" to "player-a", "M1" to "player-b", "M2" to "player-a", "M3" to "player-b"),
            firstCollectionSamples
                .map {
                    it.matchId to
                        it.puuid
                }.toSet(),
        )
        assertEquals("GOLD", firstCollectionSamples.single { it.matchId == "M1" && it.puuid == "player-a" }.tier)
        assertEquals("PLATINUM", firstCollectionSamples.single { it.matchId == "M1" && it.puuid == "player-b" }.tier)
        assertTrue(firstCollectionSamples.filter { it.puuid == "player-a" }.all { it.rankCapturedAt == playerA.rankCapturedAt })
        assertTrue(firstCollectionSamples.all { it.collectedAt == collectedAt })
    }

    @Test
    fun `skips samples with an invalid queue position or missing participant`() {
        val player = sampledPlayer("player-a")
        `when`(riotMatchClient.findMatchIdsByPuuid("player-a", 0, 3, 420)).thenReturn(listOf("M1", "M2", "M3"))
        `when`(riotMatchClient.findMatchById("M1"))
            .thenReturn(match("M1", participant("player-a", position = "INVALID")))
        `when`(riotMatchClient.findMatchById("M2"))
            .thenReturn(match("M2", participant("player-a", position = "TOP"), queueId = 440))
        `when`(riotMatchClient.findMatchById("M3"))
            .thenReturn(match("M3", participant("other-player", position = "TOP")))

        val result = service.collect(listOf(player), matchesPerPlayer = 3)

        assertEquals(3, result.fetchedMatches)
        assertEquals(3, result.skippedInvalidSamples)
        assertEquals(0, result.createdSamples)
        verify(benchmarkSamplePersistenceService, never()).saveIfAbsent(anyBenchmarkSample())
    }

    @Test
    fun `stops Match ID collection after a rate limit response`() {
        val rateLimitException = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", retryAfterSeconds = 9)
        `when`(riotMatchClient.findMatchIdsByPuuid("player-a", 0, 2, 420)).thenThrow(rateLimitException)

        val result = service.collect(listOf(sampledPlayer("player-a"), sampledPlayer("player-b")), matchesPerPlayer = 2)

        assertEquals(1, result.playersProcessed)
        assertEquals(1, result.playerMatchListFailures)
        assertTrue(result.rateLimitStopped)
        assertEquals(9, result.retryAfterSeconds)
        verify(riotMatchClient, never()).findMatchIdsByPuuid("player-b", 0, 2, 420)
        verify(riotMatchClient, never()).findMatchById(anyString())
    }

    @Test
    fun `stops Match ID collection when the local Riot cooldown is active`() {
        `when`(riotMatchClient.findMatchIdsByPuuid("player-a", 0, 2, 420)).thenThrow(RiotApiCooldownException(9))

        val result = service.collect(listOf(sampledPlayer("player-a"), sampledPlayer("player-b")), matchesPerPlayer = 2)

        assertEquals(1, result.playersProcessed)
        assertEquals(1, result.playerMatchListFailures)
        assertTrue(result.rateLimitStopped)
        assertEquals(9, result.retryAfterSeconds)
        verify(riotMatchClient, never()).findMatchIdsByPuuid("player-b", 0, 2, 420)
    }

    @Test
    fun `stops new Detail scheduling after a rate limit while retaining completed samples`() {
        val player = sampledPlayer("player-a")
        `when`(riotMatchClient.findMatchIdsByPuuid("player-a", 0, 6, 420))
            .thenReturn(listOf("M1", "M429", "M2", "M3", "M4", "M5"))
        val otherInitialDetailsStarted = CountDownLatch(2)
        val completedSample = CountDownLatch(1)
        val fourthDetailStarted = CountDownLatch(1)
        val releaseOtherDetails = CountDownLatch(1)
        val rateLimitException = RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", retryAfterSeconds = 7)
        `when`(riotMatchClient.findMatchById("M1"))
            .thenAnswer {
                assertTrue(otherInitialDetailsStarted.await(2, TimeUnit.SECONDS))
                completedSample.countDown()
                match("M1", participant("player-a", position = "TOP"))
            }
        `when`(riotMatchClient.findMatchById("M429"))
            .thenAnswer {
                assertTrue(completedSample.await(2, TimeUnit.SECONDS))
                assertTrue(fourthDetailStarted.await(2, TimeUnit.SECONDS))
                throw rateLimitException
            }
        listOf("M2", "M3", "M4").forEach { matchId ->
            `when`(riotMatchClient.findMatchById(matchId))
                .thenAnswer {
                    if (matchId == "M2" || matchId == "M3") {
                        otherInitialDetailsStarted.countDown()
                    }
                    if (matchId == "M4") {
                        fourthDetailStarted.countDown()
                    }
                    assertTrue(releaseOtherDetails.await(2, TimeUnit.SECONDS))
                    match(matchId, participant("player-a", position = "TOP"))
                }
        }
        `when`(benchmarkSamplePersistenceService.saveIfAbsent(anyBenchmarkSample()))
            .thenReturn(BenchmarkSampleSaveResult.INSERTED)

        try {
            val result = service.collect(listOf(player), matchesPerPlayer = 6)

            assertEquals(1, result.createdSamples)
            assertEquals(1, result.fetchedMatches)
            assertEquals(1, result.failedMatches)
            assertTrue(result.rateLimitStopped)
            assertEquals(7, result.retryAfterSeconds)
            verify(riotMatchClient, never()).findMatchById("M5")
            assertFalse(result.createdSamples == 0)
        } finally {
            releaseOtherDetails.countDown()
        }
    }

    private fun sampledPlayer(
        puuid: String,
        tier: String = "GOLD",
        division: String = "I",
    ): SampledRankedPlayer =
        SampledRankedPlayer(
            puuid = puuid,
            region = "KR",
            queue = "RANKED_SOLO_5x5",
            tier = tier,
            division = division,
            rankCapturedAt = Instant.parse("2026-09-13T12:34:56Z"),
        )

    private fun match(
        matchId: String,
        vararg participants: MatchParticipant,
        queueId: Int = 420,
    ): Match =
        Match(
            matchId = matchId,
            queueId = queueId,
            gameMode = "CLASSIC",
            gameVersion = "16.18.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-09-13T09:00:00Z"),
            endedAt = Instant.parse("2026-09-13T09:30:00Z"),
            duration = Duration.ofMinutes(30),
            participants = participants.toList(),
            teams = emptyList(),
        )

    private fun participant(
        puuid: String,
        teamId: Int = 100,
        position: String,
        kills: Int = 4,
        deaths: Int = 2,
        assists: Int = 6,
        championId: Int = 103,
    ): MatchParticipant =
        MatchParticipant(
            participantId = participantId++,
            puuid = puuid,
            riotId = RiotIdSnapshot("Player", "KR1"),
            teamId = teamId,
            won = teamId == 100,
            position = position,
            champion = MatchChampion(championId, "Ahri", 18),
            kills = kills,
            deaths = deaths,
            assists = assists,
            pentaKills = 0,
            laneMinionKills = 150,
            neutralMinionKills = 10,
            goldEarned = 12_000,
            championDamageDealt = 20_000,
            damageTaken = 10_000,
            vision = MatchVision(score = 25, wardsPlaced = 8, wardsKilled = 2),
            turretKills = 1,
            itemIdsBySlot = emptyList(),
            summonerSpellIds = emptyList(),
            perks = MatchPerks(0, 0, 0, emptyList()),
            reportedChallenges = null,
        )

    private fun anyBenchmarkSample(): BenchmarkSample = any(BenchmarkSample::class.java) ?: benchmarkSample()

    private fun capture(captor: ArgumentCaptor<BenchmarkSample>): BenchmarkSample = captor.capture() ?: benchmarkSample()

    private fun benchmarkSample(): BenchmarkSample =
        BenchmarkSample(
            matchId = "matcher-match",
            puuid = "matcher-puuid",
            region = "KR",
            queueId = 420,
            tier = "GOLD",
            division = "I",
            rankCapturedAt = Instant.parse("2026-09-13T12:34:56Z"),
            championId = 103,
            position = "MIDDLE",
            gameVersion = "16.18.1",
            gameStartTimestamp = Instant.parse("2026-09-13T09:00:00Z"),
            kills = 0,
            deaths = 0,
            assists = 0,
            kda = 0.0,
            csPerMinute = 0.0,
            goldPerMinute = 0.0,
            damagePerMinute = 0.0,
            visionPerMinute = 0.0,
            killParticipation = 0.0,
            damageShare = 0.0,
            collectedAt = collectedAt,
        )

    private companion object {
        var participantId = 1
    }
}
