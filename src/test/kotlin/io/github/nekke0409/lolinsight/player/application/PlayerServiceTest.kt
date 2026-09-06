package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class PlayerServiceTest {
    private val riotAccountClient = mock(RiotAccountClient::class.java)
    private val playerService = PlayerService(riotAccountClient)

    @Test
    fun `finds a player by Riot ID and maps the Account API response`() {
        `when`(riotAccountClient.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(
                RiotAccountResponse(
                    puuid = "test-puuid",
                    gameName = "Hide on bush",
                    tagLine = "KR1",
                ),
            )

        val response = playerService.findByRiotId(gameName = "Hide on bush", tagLine = "KR1")

        verify(riotAccountClient).findByRiotId("Hide on bush", "KR1")
        assertEquals("test-puuid", response.puuid)
        assertEquals("Hide on bush", response.gameName)
        assertEquals("KR1", response.tagLine)
    }
}
