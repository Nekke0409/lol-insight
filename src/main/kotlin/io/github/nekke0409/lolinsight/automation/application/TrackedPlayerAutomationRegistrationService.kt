package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.application.PlayerService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class TrackedPlayerAutomationRegistrationService(
    private val playerService: PlayerService,
    private val riotMatchClient: RiotMatchClient,
    private val trackedPlayerAutomationService: TrackedPlayerAutomationService,
    private val clock: Clock,
) {
    /**
     * Resolves a Riot ID only for registration, then persists the stable PUUID and the current
     * Ranked Solo head as a baseline. Historical matches therefore never become automation work.
     */
    fun register(
        gameName: String,
        tagLine: String,
    ): TrackedPlayerAutomation {
        val player = playerService.findByRiotId(gameName, tagLine)
        val initialCursor =
            riotMatchClient
                .findMatchIdsByPuuid(
                    puuid = player.puuid,
                    start = ANALYSIS_START,
                    count = ANALYSIS_COUNT,
                    queue = RankedSoloQueue.ID,
                ).firstOrNull()
        val checkedAt = Instant.now(clock)

        return trackedPlayerAutomationService.createIfAbsent(player.puuid, initialCursor, checkedAt)
    }

    private companion object {
        const val ANALYSIS_START = 0
        const val ANALYSIS_COUNT = 20
    }
}
