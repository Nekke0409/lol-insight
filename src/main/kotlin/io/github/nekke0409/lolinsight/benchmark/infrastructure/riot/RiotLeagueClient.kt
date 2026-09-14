package io.github.nekke0409.lolinsight.benchmark.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiRouting
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class RiotLeagueClient(
    private val riotApiHttpClient: RiotApiHttpClient,
) {
    fun findRankedPlayerPuuids(
        tier: String,
        division: String,
        playerLimit: Int,
    ): List<String> {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(playerLimit > 0) { "playerLimit must be positive" }

        val puuids = mutableListOf<String>()
        var page = FIRST_PAGE

        while (puuids.size < playerLimit) {
            val entries = findRankedEntries(tier, division, page)
            if (entries.isEmpty()) {
                break
            }

            entries.forEach { entry ->
                if (puuids.size == playerLimit) {
                    return puuids
                }

                findPuuidBySummonerId(entry.summonerId)?.let(puuids::add)
            }
            page += 1
        }

        return puuids
    }

    private fun findRankedEntries(
        tier: String,
        division: String,
        page: Int,
    ): List<RiotLeagueEntryDto> =
        try {
            riotApiHttpClient
                .get(
                    routing = RiotApiRouting.PLATFORM,
                    path = LEAGUE_ENTRIES_PATH,
                    uriVariables =
                        mapOf(
                            "queue" to RANKED_SOLO_QUEUE,
                            "tier" to tier,
                            "division" to division,
                        ),
                    queryParameters = mapOf("page" to page),
                    responseType = Array<RiotLeagueEntryDto>::class.java,
                ).toList()
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.NOT_FOUND.value()) {
                emptyList()
            } else {
                throw exception
            }
        }

    private fun findPuuidBySummonerId(summonerId: String): String? =
        try {
            riotApiHttpClient
                .get(
                    routing = RiotApiRouting.PLATFORM,
                    path = SUMMONER_BY_ID_PATH,
                    uriVariables = mapOf("summonerId" to summonerId),
                    responseType = RiotSummonerDto::class.java,
                ).puuid
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.NOT_FOUND.value()) {
                null
            } else {
                throw exception
            }
        }

    private companion object {
        const val FIRST_PAGE = 1
        const val RANKED_SOLO_QUEUE = "RANKED_SOLO_5x5"
        const val LEAGUE_ENTRIES_PATH = "/lol/league/v4/entries/{queue}/{tier}/{division}"
        const val SUMMONER_BY_ID_PATH = "/lol/summoner/v4/summoners/{summonerId}"
    }
}
