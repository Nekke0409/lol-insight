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

        return account.toPlayerResponse()
    }

    fun findByPuuid(puuid: String): PlayerResponse {
        val account = riotAccountClient.findByPuuid(puuid)

        return account.toPlayerResponse()
    }

    private fun io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountResponse.toPlayerResponse(): PlayerResponse =
        PlayerResponse(
            puuid = puuid,
            gameName = gameName,
            tagLine = tagLine,
        )
}
