package io.github.nekke0409.lolinsight.benchmark.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiInvalidResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiRouting
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.rank.application.CurrentRankedSoloRank
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class RiotLeagueClient(
    private val riotApiHttpClient: RiotApiHttpClient,
) {
    fun findCurrentRankedSoloRank(puuid: String): CurrentRankedSoloRank? {
        require(puuid.isNotBlank()) { "puuid must not be blank" }

        val entry = findRankedEntriesByPuuid(puuid).firstOrNull { it.queueType == RankedSoloQueue.TYPE } ?: return null

        return try {
            CurrentRankedSoloRank(tier = entry.tier, division = entry.rank)
        } catch (exception: IllegalArgumentException) {
            throw RiotApiInvalidResponseException(exception)
        }
    }

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
            val pagePuuids = findRankedPlayerPuuidsOnPage(tier, division, page)
            if (pagePuuids.isEmpty()) {
                break
            }

            pagePuuids.forEach { puuid ->
                if (puuids.size == playerLimit) {
                    return puuids
                }

                puuids += puuid
            }
            page += 1
        }

        return puuids
    }

    fun findRankedPlayerPuuidsOnPage(
        tier: String,
        division: String,
        page: Int,
    ): List<String> {
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(page > 0) { "page must be positive" }

        return findRankedEntries(tier, division, page).map(RiotLeagueEntryDto::puuid)
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
                            "queue" to RankedSoloQueue.TYPE,
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

    private fun findRankedEntriesByPuuid(puuid: String): List<RiotLeagueEntryDto> =
        try {
            riotApiHttpClient
                .get(
                    routing = RiotApiRouting.PLATFORM,
                    path = LEAGUE_ENTRIES_BY_PUUID_PATH,
                    uriVariables = mapOf("puuid" to puuid),
                    responseType = Array<RiotLeagueEntryDto>::class.java,
                ).toList()
        } catch (exception: RiotApiResponseException) {
            if (exception.statusCode.value() == HttpStatus.NOT_FOUND.value()) {
                emptyList()
            } else {
                throw exception
            }
        }

    private companion object {
        const val FIRST_PAGE = 1
        const val LEAGUE_ENTRIES_PATH = "/lol/league/v4/entries/{queue}/{tier}/{division}"
        const val LEAGUE_ENTRIES_BY_PUUID_PATH = "/lol/league/v4/entries/by-puuid/{puuid}"
    }
}
