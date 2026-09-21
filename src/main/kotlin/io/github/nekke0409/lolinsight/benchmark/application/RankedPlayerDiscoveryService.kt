package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiException
import io.github.nekke0409.lolinsight.global.riot.rateLimitRetryAfterSeconds
import io.github.nekke0409.lolinsight.global.riot.requiresCollectionStop
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class RankedPlayerDiscoveryService(
    private val riotLeagueClient: RiotLeagueClient,
    private val clock: Clock,
) {
    fun discover(
        tier: String,
        division: String,
        playerLimit: Int,
    ): List<SampledRankedPlayer> {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(playerLimit > 0) { "playerLimit must be positive" }

        val rankCapturedAt = clock.instant()

        return riotLeagueClient
            .findRankedPlayerPuuids(tier, division, playerLimit)
            .take(playerLimit)
            .map { puuid ->
                SampledRankedPlayer(
                    puuid = puuid,
                    region = KR_REGION,
                    queue = RankedSoloQueue.TYPE,
                    tier = tier,
                    division = division,
                    rankCapturedAt = rankCapturedAt,
                )
            }
    }

    fun discoverPaged(
        tier: String,
        division: String,
        startPage: Int,
        pageCount: Int,
    ): PagedRankedPlayerDiscoveryResult {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(startPage > 0) { "startPage must be positive" }
        require(pageCount > 0) { "pageCount must be positive" }
        require(startPage <= Int.MAX_VALUE - pageCount + 1) { "requested page range is too large" }
        val rankCapturedAt = clock.instant()
        val playersByPuuid = linkedMapOf<String, SampledRankedPlayer>()
        var discoveredPlayers = 0
        var pagesProcessed = 0

        for (page in startPage until startPage + pageCount) {
            val pagePuuids =
                try {
                    riotLeagueClient.findRankedPlayerPuuidsOnPage(tier, division, page)
                } catch (exception: RiotApiException) {
                    if (exception.requiresCollectionStop()) {
                        return PagedRankedPlayerDiscoveryResult(
                            players = playersByPuuid.values.toList(),
                            discoveredPlayers = discoveredPlayers,
                            pagesProcessed = pagesProcessed,
                            rateLimitStopped = true,
                            retryAfterSeconds = exception.rateLimitRetryAfterSeconds(),
                            emptyPageEncountered = false,
                        )
                    }
                    throw exception
                }

            pagesProcessed += 1
            if (pagePuuids.isEmpty()) {
                return PagedRankedPlayerDiscoveryResult(
                    players = playersByPuuid.values.toList(),
                    discoveredPlayers = discoveredPlayers,
                    pagesProcessed = pagesProcessed,
                    rateLimitStopped = false,
                    retryAfterSeconds = null,
                    emptyPageEncountered = true,
                )
            }

            for (puuid in pagePuuids.sorted()) {
                discoveredPlayers += 1
                playersByPuuid.putIfAbsent(
                    puuid,
                    SampledRankedPlayer(
                        puuid = puuid,
                        region = KR_REGION,
                        queue = RankedSoloQueue.TYPE,
                        tier = tier,
                        division = division,
                        rankCapturedAt = rankCapturedAt,
                    ),
                )
            }
        }

        return PagedRankedPlayerDiscoveryResult(
            players = playersByPuuid.values.toList(),
            discoveredPlayers = discoveredPlayers,
            pagesProcessed = pagesProcessed,
            rateLimitStopped = false,
            retryAfterSeconds = null,
            emptyPageEncountered = false,
        )
    }

    private companion object {
        const val KR_REGION = "KR"
    }
}

data class PagedRankedPlayerDiscoveryResult(
    val players: List<SampledRankedPlayer>,
    val discoveredPlayers: Int,
    val pagesProcessed: Int,
    val rateLimitStopped: Boolean,
    val retryAfterSeconds: Long?,
    val emptyPageEncountered: Boolean = false,
) {
    init {
        require(discoveredPlayers >= uniquePlayers) { "discoveredPlayers cannot be less than unique players" }
        require(pagesProcessed >= 0) { "pagesProcessed cannot be negative" }
    }

    val uniquePlayers: Int
        get() = players.map(SampledRankedPlayer::puuid).distinct().size
}
