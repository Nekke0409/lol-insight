package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class PlayerControllerTest {
    private val playerService = mock(PlayerService::class.java)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(PlayerController(playerService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `returns player lookup response`() {
        `when`(playerService.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(
                PlayerResponse(
                    puuid = "test-puuid",
                    gameName = "Hide on bush",
                    tagLine = "KR1",
                ),
            )

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.puuid").value("test-puuid"))
            .andExpect(jsonPath("$.gameName").value("Hide on bush"))
            .andExpect(jsonPath("$.tagLine").value("KR1"))
    }
}
