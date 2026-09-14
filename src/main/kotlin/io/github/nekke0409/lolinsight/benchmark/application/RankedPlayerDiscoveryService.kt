package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
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
                    queue = RANKED_SOLO_QUEUE,
                    tier = tier,
                    division = division,
                    rankCapturedAt = rankCapturedAt,
                )
            }
    }

    private companion object {
        const val KR_REGION = "KR"
        const val RANKED_SOLO_QUEUE = "RANKED_SOLO_5x5"
    }
}
