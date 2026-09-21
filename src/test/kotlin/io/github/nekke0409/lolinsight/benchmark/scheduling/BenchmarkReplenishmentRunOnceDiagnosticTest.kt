package io.github.nekke0409.lolinsight.benchmark.scheduling

import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityPolicy
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityProperties
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkCollectionResult
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentAttempt
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentAttemptOutcome
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentCohort
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentPlanner
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentTickOutcome
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentTickResult
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSeedResult
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.ApplicationArguments
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BenchmarkReplenishmentRunOnceDiagnosticTest {
    private val planner = BenchmarkReplenishmentPlanner(BenchmarkAvailabilityPolicy(BenchmarkAvailabilityProperties()))
    private val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
    private val window =
        BenchmarkQueryWindow(
            Instant.parse("2026-08-20T00:00:00Z"),
            Instant.parse("2026-09-19T00:00:00Z"),
        )

    @Test
    fun `projects only the one tick diagnostic fields into a structured summary`() {
        val before = planner.plan(cohort, window, emptyList())
        val after = planner.plan(cohort, window, availableCoverage())
        val result =
            BenchmarkReplenishmentTickResult(
                outcome = BenchmarkReplenishmentTickOutcome.COMPLETED,
                before = listOf(before),
                after = listOf(after),
                attempts =
                    listOf(
                        BenchmarkReplenishmentAttempt(
                            cohort = cohort,
                            requestedPage = 5,
                            nextPage = 6,
                            outcome = BenchmarkReplenishmentAttemptOutcome.COMPLETED,
                            seedResult =
                                BenchmarkSeedResult(
                                    requestedStartPage = 5,
                                    requestedPageCount = 3,
                                    discoveredPlayers = 10,
                                    candidatePlayers = 10,
                                    uniquePlayers = 10,
                                    selectedZeroValidSamplePlayers = 10,
                                    selectedExistingValidSamplePlayers = 0,
                                    pagesProcessed = 1,
                                    collectionResult =
                                        BenchmarkCollectionResult(
                                            inputPlayers = 10,
                                            playersProcessed = 10,
                                            playerMatchListFailures = 0,
                                            discoveredMatchIds = 20,
                                            uniqueMatchIds = 15,
                                            fetchedMatches = 15,
                                            failedMatches = 0,
                                            createdSamples = 8,
                                            skippedDuplicates = 2,
                                            skippedInvalidSamples = 1,
                                            rateLimitStopped = false,
                                            retryAfterSeconds = null,
                                        ),
                                    rateLimitStopped = false,
                                    retryAfterSeconds = null,
                                ),
                        ),
                    ),
            )

        val summary = BenchmarkReplenishmentRunOnceDiagnosticSummary.from(result)
        val objectMapper = JsonMapper.builder().findAndAddModules().build()
        val json = objectMapper.writeValueAsString(summary)
        val queryWindow = checkNotNull(summary.queryWindow)
        val cohortDiagnostic = summary.cohorts.single()
        val beforeTop = cohortDiagnostic.before.first()
        val afterTop = cohortDiagnostic.after.first()
        val attempt = summary.attempts.single()

        assertEquals("COMPLETED", summary.tickOutcome)
        assertEquals(window.fromInclusive, queryWindow.fromInclusive)
        assertEquals(window.toExclusive, queryWindow.toExclusive)
        assertEquals("GOLD:I", cohortDiagnostic.cohort)
        assertEquals(5, cohortDiagnostic.before.size)
        assertEquals(5, cohortDiagnostic.after.size)
        assertEquals("TOP", beforeTop.position)
        assertEquals(0, beforeTop.sampleCount)
        assertEquals(0, beforeTop.uniquePlayerCount)
        assertEquals("NO_DATA", beforeTop.availability)
        assertEquals(30, beforeTop.samplesNeeded)
        assertEquals(10, beforeTop.uniquePlayersNeeded)
        assertEquals(30, afterTop.sampleCount)
        assertEquals(10, afterTop.uniquePlayerCount)
        assertEquals("AVAILABLE", afterTop.availability)
        assertEquals(0, afterTop.samplesNeeded)
        assertEquals(0, afterTop.uniquePlayersNeeded)
        assertEquals(5, attempt.requestedPage)
        assertEquals(1, attempt.pagesProcessed)
        assertEquals(6, attempt.nextPage)
        assertEquals(10, attempt.discoveredPlayers)
        assertEquals(10, attempt.candidatePlayers)
        assertEquals(10, attempt.selectedPlayers)
        assertEquals(10, attempt.selectedZeroValidSamplePlayers)
        assertEquals(0, attempt.selectedExistingValidSamplePlayers)
        assertEquals("COMPLETED", attempt.discoveryOutcome)
        assertEquals("COMPLETED", attempt.collectionOutcome)
        assertEquals(8, attempt.createdSamples)
        assertEquals(2, attempt.skippedDuplicates)
        assertEquals(1, attempt.skippedInvalidSamples)
        assertFalse(json.contains("puuid", ignoreCase = true))
        assertFalse(json.contains("riotId", ignoreCase = true))
        assertFalse(json.contains("matchId", ignoreCase = true))
        assertFalse(json.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun `run once runner logs the returned tick result`() {
        val tickService = mock(io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentTickService::class.java)
        val diagnosticLogger = mock(BenchmarkReplenishmentRunOnceDiagnosticLogger::class.java)
        val result =
            BenchmarkReplenishmentTickResult(
                outcome = BenchmarkReplenishmentTickOutcome.HEALTHY,
                before = emptyList(),
                after = emptyList(),
                attempts = emptyList(),
            )
        `when`(tickService.runOneTick()).thenReturn(result)

        BenchmarkReplenishmentRunOnceConfiguration()
            .benchmarkReplenishmentRunOnceRunner(tickService, diagnosticLogger)
            .run(mock(ApplicationArguments::class.java))

        verify(tickService).runOneTick()
        verify(diagnosticLogger).log(result)
    }

    private fun availableCoverage(): List<BenchmarkCohortCoverage> =
        BenchmarkReplenishmentPlanner.POSITIONS.map { position ->
            BenchmarkCohortCoverage(
                cohort =
                    BenchmarkCohort.position(
                        region = "KR",
                        queueId = cohort.coverageScope().queueId,
                        tier = cohort.tier,
                        division = cohort.division,
                        position = position,
                    ),
                sampleCount = 30,
                uniquePlayerCount = 10,
                availability = BenchmarkAvailability.AVAILABLE,
                samplesNeeded = 0,
                uniquePlayersNeeded = 0,
            )
        }
}
