package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class TrackedPlayerAutomationRegistrationServiceTest {
    private val playerService = mock(PlayerService::class.java)
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val trackedPlayerAutomationService = mock(TrackedPlayerAutomationService::class.java)
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val service =
        TrackedPlayerAutomationRegistrationService(
            playerService,
            riotMatchClient,
            trackedPlayerAutomationService,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `registration baselines the current Ranked Solo match without creating a job`() {
        `when`(playerService.findByRiotId(GAME_NAME, TAG_LINE)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_100", "KR_99"))
        `when`(trackedPlayerAutomationService.createIfAbsent(PUUID, "KR_100", now)).thenReturn(AUTOMATION)

        assertEquals(AUTOMATION, service.register(GAME_NAME, TAG_LINE))

        verify(playerService).findByRiotId(GAME_NAME, TAG_LINE)
        verify(riotMatchClient).findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)
        verify(trackedPlayerAutomationService).createIfAbsent(PUUID, "KR_100", now)
    }

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        const val PUUID = "stable-puuid"
        val AUTOMATION =
            TrackedPlayerAutomation(
                UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf"),
                PUUID,
                true,
                "KR_100",
                Instant.parse("2026-09-18T12:00:00Z"),
                Instant.parse("2026-09-18T12:00:00Z"),
                Instant.parse("2026-09-18T12:00:00Z"),
            )
    }
}
