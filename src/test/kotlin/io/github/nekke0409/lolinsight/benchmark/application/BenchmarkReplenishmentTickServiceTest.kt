package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkReplenishmentTickServiceTest {
    private val properties =
        BenchmarkReplenishmentProperties(
            maxCohortsPerTick = 1,
            pageCountPerCohort = 1,
            playerLimitPerCohort = 10,
            matchesPerPlayer = 5,
            cohorts = listOf("GOLD:I", "PLATINUM:I"),
        )
    private val planner = BenchmarkReplenishmentPlanner(BenchmarkAvailabilityPolicy(BenchmarkAvailabilityProperties()))
    private val coverageQueryService = mock(BenchmarkCohortCoverageQueryService::class.java)
    private val windowFactory = mock(BenchmarkQueryWindowFactory::class.java)
    private val cursorService = mock(BenchmarkReplenishmentCursorService::class.java)
    private val seedService = mock(BenchmarkSeedService::class.java)
    private val cooldown = mock(RiotApiCooldown::class.java)
    private val observationRecorder = mock(BenchmarkReplenishmentObservationRecorder::class.java)
    private val service =
        BenchmarkReplenishmentTickService(
            properties,
            planner,
            coverageQueryService,
            windowFactory,
            cursorService,
            seedService,
            cooldown,
            observationRecorder,
        )

    @Test
    fun `skips all collection when the shared Riot cooldown is active`() {
        `when`(windowFactory.current()).thenReturn(window())
        stubEmptyCoverage()
        `when`(cooldown.isBlocked()).thenReturn(true)

        val result = service.runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COOLDOWN_SKIPPED, result.outcome)
        verifyNoInteractions(seedService)
        assertTrue(result.before.all { it.status == BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT })
    }

    @Test
    fun `selects only the configured bounded number of cohorts and passes the collection budget`() {
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        `when`(windowFactory.current()).thenReturn(window())
        stubEmptyCoverage()
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(BenchmarkReplenishmentCursor(cohort, 5, null))
        `when`(seedService.seed(request(cohort, 5))).thenReturn(seedResult())

        val result = service.runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.attempts.size)
        verify(seedService).seed(request(cohort, 5))
        verify(cursorService).advance(BenchmarkReplenishmentCursor(cohort, 5, null), 6)
    }

    @Test
    fun `keeps a discovery page for a later tick when discovery is rate limited`() {
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val cursor = BenchmarkReplenishmentCursor(cohort, 5, null)
        `when`(windowFactory.current()).thenReturn(window())
        stubEmptyCoverage()
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor)
        `when`(seedService.seed(request(cohort, 5))).thenReturn(seedResult(rateLimitStopped = true, collectionResult = null))

        val result = service.runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.RATE_LIMIT_STOPPED, result.outcome)
        assertEquals(BenchmarkReplenishmentAttemptOutcome.DISCOVERY_RATE_LIMITED, result.attempts.single().outcome)
        assertEquals(5, result.attempts.single().nextPage)
        verify(cursorService).advance(cursor, 5)
    }

    @Test
    fun `wraps the cursor after an empty page instead of repeating that page`() {
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val cursor = BenchmarkReplenishmentCursor(cohort, 8, null)
        `when`(windowFactory.current()).thenReturn(window())
        stubEmptyCoverage()
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor)
        `when`(seedService.seed(request(cohort, 8))).thenReturn(seedResult(emptyPageEncountered = true))

        val result = service.runOneTick()

        assertEquals(BenchmarkReplenishmentAttemptOutcome.EMPTY_PAGE_WRAPPED, result.attempts.single().outcome)
        verify(cursorService).advance(cursor, 1)
    }

    @Test
    fun `does not continue with a later cohort after a collection rate limit`() {
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        `when`(windowFactory.current()).thenReturn(window())
        stubEmptyCoverage()
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(BenchmarkReplenishmentCursor(cohort, 1, null))
        `when`(seedService.seed(request(cohort, 1))).thenReturn(seedResult(rateLimitStopped = true))

        val result = service.runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.RATE_LIMIT_STOPPED, result.outcome)
        verify(seedService, times(1)).seed(request(cohort, 1))
    }

    private fun window(): BenchmarkQueryWindow =
        BenchmarkQueryWindow(
            Instant.parse("2026-08-20T00:00:00Z"),
            Instant.parse("2026-09-19T00:00:00Z"),
        )

    private fun stubEmptyCoverage() {
        val window = window()
        properties.supportedCohorts().forEach { cohort ->
            `when`(coverageQueryService.findCoverage(cohort.coverageScope(), window)).thenReturn(emptyList())
        }
    }

    private fun request(
        cohort: BenchmarkReplenishmentCohort,
        startPage: Int,
    ) = BenchmarkSeedRequest(
        tier = cohort.tier,
        division = cohort.division,
        startPage = startPage,
        pageCount = 1,
        playerLimit = 10,
        matchesPerPlayer = 5,
    )

    private fun seedResult(
        rateLimitStopped: Boolean = false,
        collectionResult: BenchmarkCollectionResult? = collectionResult(),
        emptyPageEncountered: Boolean = false,
    ) = BenchmarkSeedResult(
        requestedStartPage = 1,
        requestedPageCount = 1,
        discoveredPlayers = 0,
        uniquePlayers = 0,
        pagesProcessed = 1,
        collectionResult = collectionResult,
        rateLimitStopped = rateLimitStopped,
        retryAfterSeconds = null,
        emptyPageEncountered = emptyPageEncountered,
    )

    private fun collectionResult() =
        BenchmarkCollectionResult(
            inputPlayers = 0,
            playersProcessed = 0,
            playerMatchListFailures = 0,
            discoveredMatchIds = 0,
            uniqueMatchIds = 0,
            fetchedMatches = 0,
            failedMatches = 0,
            createdSamples = 0,
            skippedDuplicates = 0,
            skippedInvalidSamples = 0,
            rateLimitStopped = false,
            retryAfterSeconds = null,
        )
}
