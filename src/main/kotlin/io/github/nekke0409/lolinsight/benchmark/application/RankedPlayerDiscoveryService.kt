package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import io.github.nekke0409.lolinsight.benchmark.infrastructure.riot.RiotLeagueClient
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

    private companion object {
        const val KR_REGION = "KR"
    }
}
