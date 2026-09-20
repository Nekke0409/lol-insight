package io.github.nekke0409.lolinsight.agent.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentSmokeObservationRecorderTest {
    @Test
    fun `keeps only permitted stats tool fields and normalizes an arbitrary tool name`() {
        val observation =
            AgentToolDispatchResult(
                toolName = "untrusted-tool\nsecond-line",
                success = true,
                payload =
                    AgentRankedStatsToolResult(
                        status = "AVAILABLE",
                        scope = "POSITION",
                        sample = AgentSampleResult(requestedMatchCount = 20, analyzedMatchCount = 7),
                        statistics =
                            listOf(
                                AgentScopedStatisticsResult(
                                    scope = "POSITION",
                                    position = "MIDDLE",
                                    champion = null,
                                    games = 7,
                                    wins = 4,
                                    losses = 3,
                                    winRate = 4.0 / 7.0,
                                    averageKda = 3.2,
                                    averageCsPerMinute = 7.1,
                                    averageGoldPerMinute = 410.0,
                                    averageDamagePerMinute = 610.0,
                                    averageVisionPerMinute = 1.2,
                                    averageKillParticipation = 0.48,
                                    averageDamageShare = 0.24,
                                ),
                            ),
                        metricUnits = AgentMetricUnits(),
                        limitations = emptyList(),
                    ),
                limitations = emptyList(),
            ).toSmokeObservation()

        assertEquals("UNSUPPORTED_TOOL", observation.toolName)
        assertEquals("POSITION", observation.groupBy)
        assertEquals(20, observation.requestedMatchCount)
        assertEquals(7, observation.analyzedMatchCount)
        assertEquals(
            AgentSmokeStatisticsObservation("MIDDLE", 7, 4.0 / 7.0, 3.2, 7.1),
            observation.statistics.single(),
        )
        assertFalse(observation.toString().contains("untrusted-tool"))
        assertFalse(observation.toString().contains("champion"))
    }
}
