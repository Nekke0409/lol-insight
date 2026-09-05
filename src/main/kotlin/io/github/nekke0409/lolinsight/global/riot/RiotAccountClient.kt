package io.github.nekke0409.lolinsight.global.riot

import org.springframework.stereotype.Component

@Component
class RiotAccountClient(
    private val riotApiHttpClient: RiotApiHttpClient,
) {
    fun findByRiotId(
        gameName: String,
        tagLine: String,
    ): RiotAccountResponse =
        riotApiHttpClient.get(
            routing = RiotApiRouting.REGIONAL,
            path = "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}",
            uriVariables = mapOf("gameName" to gameName, "tagLine" to tagLine),
            responseType = RiotAccountResponse::class.java,
        )
}
