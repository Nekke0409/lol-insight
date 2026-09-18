package io.github.nekke0409.lolinsight.player.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiRouting
import io.github.nekke0409.lolinsight.player.application.PlayerNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class RiotAccountClient(
    private val riotApiHttpClient: RiotApiHttpClient,
) {
    fun findByRiotId(
        gameName: String,
        tagLine: String,
    ): RiotAccountResponse =
        try {
            riotApiHttpClient.get(
                routing = RiotApiRouting.REGIONAL,
                path = "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}",
                uriVariables = mapOf("gameName" to gameName, "tagLine" to tagLine),
                responseType = RiotAccountResponse::class.java,
            )
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.NOT_FOUND.value()) {
                throw PlayerNotFoundException(exception)
            }
            throw exception
        }

    fun findByPuuid(puuid: String): RiotAccountResponse =
        try {
            riotApiHttpClient.get(
                routing = RiotApiRouting.REGIONAL,
                path = "/riot/account/v1/accounts/by-puuid/{puuid}",
                uriVariables = mapOf("puuid" to puuid),
                responseType = RiotAccountResponse::class.java,
            )
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.NOT_FOUND.value()) {
                throw PlayerNotFoundException(exception)
            }
            throw exception
        }
}
