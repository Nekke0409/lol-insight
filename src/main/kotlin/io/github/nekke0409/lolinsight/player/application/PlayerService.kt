package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import org.springframework.stereotype.Service

@Service
class PlayerService(
    private val riotAccountClient: RiotAccountClient,
) {
    fun findByRiotId(
        gameName: String,
        tagLine: String,
    ): PlayerResponse {
        val account = riotAccountClient.findByRiotId(gameName, tagLine)

        return PlayerResponse(
            puuid = account.puuid,
            gameName = account.gameName,
            tagLine = account.tagLine,
        )
    }
}
