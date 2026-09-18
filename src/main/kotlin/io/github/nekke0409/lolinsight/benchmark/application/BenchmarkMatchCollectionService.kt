package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.global.riot.RiotApiException
import io.github.nekke0409.lolinsight.global.riot.isRateLimited
import io.github.nekke0409.lolinsight.global.riot.rateLimitRetryAfterSeconds
import io.github.nekke0409.lolinsight.match.application.MatchDetailBatchLoader
import io.github.nekke0409.lolinsight.match.application.MatchDetailLoadFailure
import io.github.nekke0409.lolinsight.match.application.MatchDetailLoadSuccess
import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetricsCalculator
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class BenchmarkMatchCollectionService(
    private val riotMatchClient: RiotMatchClient,
    private val matchDetailBatchLoader: MatchDetailBatchLoader,
    private val benchmarkSamplePersistenceService: BenchmarkSamplePersistenceService,
    private val clock: Clock,
) {
    fun collect(
        players: List<SampledRankedPlayer>,
        matchesPerPlayer: Int,
    ): BenchmarkCollectionResult {
        require(matchesPerPlayer in MIN_MATCHES_PER_PLAYER..MAX_MATCHES_PER_PLAYER) {
            "matchesPerPlayer must be between $MIN_MATCHES_PER_PLAYER and $MAX_MATCHES_PER_PLAYER."
        }

        val sampledPlayersByMatchId = linkedMapOf<String, LinkedHashMap<String, SampledRankedPlayer>>()
        var playersProcessed = 0
        var playerMatchListFailures = 0
        var discoveredMatchIds = 0
        var rateLimitStopped = false
        var retryAfterSeconds: Long? = null

        for (player in players) {
            playersProcessed += 1

            val matchIds =
                try {
                    riotMatchClient.findMatchIdsByPuuid(
                        puuid = player.puuid,
                        count = matchesPerPlayer,
                        queue = RankedSoloQueue.ID,
                    )
                } catch (exception: RiotApiException) {
                    playerMatchListFailures += 1
                    if (exception.isRateLimited()) {
                        rateLimitStopped = true
                        retryAfterSeconds = exception.rateLimitRetryAfterSeconds()
                        break
                    }
                    continue
                }

            discoveredMatchIds += matchIds.size
            matchIds.forEach { matchId ->
                sampledPlayersByMatchId
                    .getOrPut(matchId, ::linkedMapOf)
                    .putIfAbsent(player.puuid, player)
            }
        }

        if (rateLimitStopped) {
            return BenchmarkCollectionResult(
                inputPlayers = players.size,
                playersProcessed = playersProcessed,
                playerMatchListFailures = playerMatchListFailures,
                discoveredMatchIds = discoveredMatchIds,
                uniqueMatchIds = sampledPlayersByMatchId.size,
                fetchedMatches = 0,
                failedMatches = 0,
                createdSamples = 0,
                skippedDuplicates = 0,
                skippedInvalidSamples = 0,
                rateLimitStopped = true,
                retryAfterSeconds = retryAfterSeconds,
            )
        }

        var fetchedMatches = 0
        var failedMatches = 0
        var createdSamples = 0
        var skippedDuplicates = 0
        var skippedInvalidSamples = 0

        val detailResults =
            matchDetailBatchLoader.load(sampledPlayersByMatchId.keys.toList()) { failure ->
                (failure.exception as? RiotApiException)?.isRateLimited() == true
            }

        detailResults.forEach { detailResult ->
            when (detailResult) {
                is MatchDetailLoadSuccess -> {
                    fetchedMatches += 1
                    val sampledPlayers = checkNotNull(sampledPlayersByMatchId[detailResult.matchId]).values
                    sampledPlayers.forEach { player ->
                        val sample = detailResult.match.toBenchmarkSample(player, clock)
                        if (sample == null) {
                            skippedInvalidSamples += 1
                        } else {
                            when (benchmarkSamplePersistenceService.saveIfAbsent(sample)) {
                                BenchmarkSampleSaveResult.INSERTED -> createdSamples += 1
                                BenchmarkSampleSaveResult.ALREADY_EXISTS -> skippedDuplicates += 1
                            }
                        }
                    }
                }

                is MatchDetailLoadFailure -> {
                    failedMatches += 1
                    val riotException = detailResult.exception as? RiotApiException
                    if (riotException?.isRateLimited() == true) {
                        rateLimitStopped = true
                        retryAfterSeconds = riotException.rateLimitRetryAfterSeconds()
                    }
                }

                else -> failedMatches += 1
            }
        }

        return BenchmarkCollectionResult(
            inputPlayers = players.size,
            playersProcessed = playersProcessed,
            playerMatchListFailures = playerMatchListFailures,
            discoveredMatchIds = discoveredMatchIds,
            uniqueMatchIds = sampledPlayersByMatchId.size,
            fetchedMatches = fetchedMatches,
            failedMatches = failedMatches,
            createdSamples = createdSamples,
            skippedDuplicates = skippedDuplicates,
            skippedInvalidSamples = skippedInvalidSamples,
            rateLimitStopped = rateLimitStopped,
            retryAfterSeconds = retryAfterSeconds,
        )
    }

    private fun Match.toBenchmarkSample(
        sampledPlayer: SampledRankedPlayer,
        clock: Clock,
    ): BenchmarkSample? {
        if (queueId != RankedSoloQueue.ID || sampledPlayer.queue != RankedSoloQueue.TYPE || matchId.isBlank() || gameVersion.isBlank()) {
            return null
        }

        val participant = participants.firstOrNull { it.puuid == sampledPlayer.puuid } ?: return null
        if (participant.champion.id <= 0 || participant.position !in ANALYZABLE_POSITIONS) {
            return null
        }

        val metrics = MatchParticipantMetricsCalculator.calculate(this, participant)
        if (
            listOf(
                metrics.kda,
                metrics.csPerMinute,
                metrics.goldPerMinute,
                metrics.damagePerMinute,
                metrics.visionPerMinute,
                metrics.killParticipation,
                metrics.damageShare,
            ).any { !it.isFinite() }
        ) {
            return null
        }

        return BenchmarkSample(
            matchId = matchId,
            puuid = sampledPlayer.puuid,
            region = sampledPlayer.region,
            queueId = queueId,
            tier = sampledPlayer.tier,
            division = sampledPlayer.division,
            rankCapturedAt = sampledPlayer.rankCapturedAt,
            championId = participant.champion.id,
            position = participant.position,
            gameVersion = gameVersion,
            gameStartTimestamp = startedAt,
            kills = participant.kills,
            deaths = participant.deaths,
            assists = participant.assists,
            kda = metrics.kda,
            csPerMinute = metrics.csPerMinute,
            goldPerMinute = metrics.goldPerMinute,
            damagePerMinute = metrics.damagePerMinute,
            visionPerMinute = metrics.visionPerMinute,
            killParticipation = metrics.killParticipation,
            damageShare = metrics.damageShare,
            collectedAt = clock.instant(),
        )
    }

    private companion object {
        const val MIN_MATCHES_PER_PLAYER = 1
        const val MAX_MATCHES_PER_PLAYER = 100
        val ANALYZABLE_POSITIONS = setOf("TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY")
    }
}
