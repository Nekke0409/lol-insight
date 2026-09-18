package io.github.nekke0409.lolinsight.match.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RiotMatchClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: RiotMatchClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()

        val properties =
            RiotApiProperties(
                key = "test-api-key",
                platformBaseUrl = URI.create("https://kr.api.riotgames.com"),
                regionalBaseUrl = URI.create("https://asia.api.riotgames.com"),
            )
        client = RiotMatchClient(RiotApiHttpClient(builder.build(), properties, RiotApiCooldown(properties, Clock.systemUTC())))
    }

    @Test
    fun `finds Match IDs with PUUID and pagination parameters using Asia regional routing`() {
        server
            .expect(
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/test-puuid/ids?start=40&count=10",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[\"KR_100\",\"KR_99\"]"),
            )

        val matchIds = client.findMatchIdsByPuuid(puuid = "test-puuid", start = 40, count = 10)

        assertEquals(listOf("KR_100", "KR_99"), matchIds)
        server.verify()
    }

    @Test
    fun `returns an empty list when no Match IDs are available`() {
        server
            .expect(
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/test-puuid/ids?start=0&count=20",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[]"),
            )

        val matchIds = client.findMatchIdsByPuuid(puuid = "test-puuid")

        assertEquals(emptyList(), matchIds)
        server.verify()
    }

    @Test
    fun `filters Match IDs by queue when requested`() {
        server
            .expect(
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/test-puuid/ids?start=0&count=2&queue=420",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[\"KR_100\",\"KR_99\"]"),
            )

        val matchIds = client.findMatchIdsByPuuid(puuid = "test-puuid", count = 2, queue = RankedSoloQueue.ID)

        assertEquals(listOf("KR_100", "KR_99"), matchIds)
        server.verify()
    }

    @Test
    fun `finds Match Detail with Asia regional routing and deserializes selected Riot fields`() {
        server
            .expect(
                requestTo("https://asia.api.riotgames.com/lol/match/v5/matches/KR_1234567890"),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(loadFixture("riot/match/match-detail.json")),
            )

        val match = client.findMatchById("KR_1234567890")

        assertEquals("KR_1234567890", match.matchId)
        assertEquals(2, match.participants.size)
        assertEquals(2, match.teams.size)

        val participant = match.participants.first()
        assertEquals("test-puuid-blue-top", participant.puuid)
        assertEquals(266, participant.champion.id)
        assertEquals("Aatrox", participant.champion.name)
        assertEquals(100, participant.teamId)
        assertEquals("TOP", participant.position)
        assertEquals(8, participant.kills)
        assertEquals(2, participant.deaths)
        assertEquals(5, participant.assists)
        assertEquals(12_345, participant.goldEarned)
        assertEquals(180, participant.laneMinionKills)
        assertEquals(12, participant.neutralMinionKills)
        assertEquals(24_680, participant.championDamageDealt)
        assertEquals(28, participant.vision.score)
        assertEquals(3_073, participant.itemIdsBySlot.first())
        assertEquals(3_364, participant.itemIdsBySlot.last())
        assertEquals(4.5, participant.reportedChallenges?.kda)
        server.verify()
    }

    @Test
    fun `converts a Match Detail API 404 response to Match not found`() {
        server
            .expect(requestTo("https://asia.api.riotgames.com/lol/match/v5/matches/KR_404"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":{\"message\":\"Data not found\",\"status_code\":404}}"),
            )

        val exception =
            assertFailsWith<MatchNotFoundException> {
                client.findMatchById("KR_404")
            }

        val responseException = assertIs<RiotApiResponseException>(exception.cause)
        assertEquals(404, responseException.statusCode.value())
        server.verify()
    }

    private fun loadFixture(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)).readText()
}
