package io.github.nekke0409.lolinsight.rank.application

import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class PlayerRankLookupService(
    private val riotLeagueClient: RiotLeagueClient,
    private val clock: Clock,
) {
    fun findCurrentRankContext(puuid: String): PlayerRankContext? =
        riotLeagueClient
            .findCurrentRankedSoloRank(puuid)
            ?.let { rank ->
                PlayerRankContext(
                    tier = rank.tier,
                    division = rank.division,
                    capturedAt = clock.instant(),
                )
            }
}

data class CurrentRankedSoloRank(
    val tier: String,
    val division: String,
) {
    init {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
    }
}

data class PlayerRankContext(
    val tier: String,
    val division: String,
    val capturedAt: Instant,
) {
    init {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
    }
}
