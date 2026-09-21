package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkReplenishmentTickServiceTest {
    private val planner = BenchmarkReplenishmentPlanner(BenchmarkAvailabilityPolicy(BenchmarkAvailabilityProperties()))
    private val coverageQueryService = mock(BenchmarkCohortCoverageQueryService::class.java)
    private val windowFactory = mock(BenchmarkQueryWindowFactory::class.java)
    private val cursorService = mock(BenchmarkReplenishmentCursorService::class.java)
    private val seedService = mock(BenchmarkSeedService::class.java)
    private val cooldown = mock(RiotApiCooldown::class.java)
    private val observationRecorder = mock(BenchmarkReplenishmentObservationRecorder::class.java)

    @Test
    fun `skips all collection when the shared Riot cooldown is active`() {
        val properties = properties()
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(true)

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COOLDOWN_SKIPPED, result.outcome)
        verifyNoInteractions(seedService)
        assertTrue(result.before.all { it.status == BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT })
    }

    @Test
    fun `stops at the configured budget even when another cohort also needs replenishment`() {
        val properties = properties(maxCohortsPerTick = 1)
        val first = BenchmarkReplenishmentCohort("GOLD", "I")
        val second = BenchmarkReplenishmentCohort("PLATINUM", "I")
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(first)).thenReturn(cursor(first, 5))
        `when`(seedService.seed(request(first, 5, properties))).thenReturn(seedResult())

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COMPLETED, result.outcome)
        assertEquals(listOf(first), result.attempts.map { it.cohort })
        verify(seedService).seed(request(first, 5, properties))
        verify(seedService, never()).seed(request(second, 1, properties))
        verify(cursorService).advance(cursor(first, 5), 6)
    }

    @Test
    fun `runs all budgeted cohorts in deterministic priority order when there is no rate limit`() {
        val properties = properties(maxCohortsPerTick = 2)
        val first = BenchmarkReplenishmentCohort("GOLD", "I")
        val second = BenchmarkReplenishmentCohort("PLATINUM", "I")
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(first)).thenReturn(cursor(first, 2))
        `when`(cursorService.getOrCreate(second)).thenReturn(cursor(second, 7))
        `when`(seedService.seed(request(first, 2, properties))).thenReturn(seedResult())
        `when`(seedService.seed(request(second, 7, properties))).thenReturn(seedResult())

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COMPLETED, result.outcome)
        assertEquals(listOf(first, second), result.attempts.map { it.cohort })
        inOrder(seedService).apply {
            verify(seedService).seed(request(first, 2, properties))
            verify(seedService).seed(request(second, 7, properties))
        }
        verifyNoMoreInteractions(seedService)
    }

    @Test
    fun `preserves the first cursor and stops later cohorts after discovery rate limiting`() {
        val properties = properties(maxCohortsPerTick = 2, pageCountPerCohort = 3)
        val first = BenchmarkReplenishmentCohort("GOLD", "I")
        val second = BenchmarkReplenishmentCohort("PLATINUM", "I")
        val firstCursor = cursor(first, 5)
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(first)).thenReturn(firstCursor)
        `when`(seedService.seed(request(first, 5, properties))).thenReturn(
            seedResult(
                requestedPageCount = 3,
                pagesProcessed = 0,
                rateLimitStopped = true,
                collectionResult = null,
                retryAfterSeconds = 7,
            ),
        )

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.RATE_LIMIT_STOPPED, result.outcome)
        assertEquals(BenchmarkReplenishmentAttemptOutcome.DISCOVERY_RATE_LIMITED, result.attempts.single().outcome)
        assertEquals(5, result.attempts.single().nextPage)
        verify(cursorService).advance(firstCursor, 5)
        verify(seedService).seed(request(first, 5, properties))
        verify(seedService, never()).seed(request(second, 1, properties))
        verifyNoMoreInteractions(seedService)
    }

    @Test
    fun `advances only completed discovery pages and stops later cohorts after collection rate limiting`() {
        val properties = properties(maxCohortsPerTick = 2, pageCountPerCohort = 3)
        val first = BenchmarkReplenishmentCohort("GOLD", "I")
        val second = BenchmarkReplenishmentCohort("PLATINUM", "I")
        val firstCursor = cursor(first, 5)
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(first)).thenReturn(firstCursor)
        `when`(seedService.seed(request(first, 5, properties))).thenReturn(
            seedResult(
                requestedPageCount = 3,
                pagesProcessed = 1,
                rateLimitStopped = true,
                collectionResult = collectionResult(rateLimitStopped = true, retryAfterSeconds = 4),
                retryAfterSeconds = 4,
            ),
        )

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.RATE_LIMIT_STOPPED, result.outcome)
        assertEquals(BenchmarkReplenishmentAttemptOutcome.COLLECTION_RATE_LIMITED, result.attempts.single().outcome)
        assertEquals(6, result.attempts.single().nextPage)
        verify(cursorService).advance(firstCursor, 6)
        verify(seedService).seed(request(first, 5, properties))
        verify(seedService, never()).seed(request(second, 1, properties))
        verifyNoMoreInteractions(seedService)
    }

    @Test
    fun `advances by actual discovery pages when candidate selection limits collection`() {
        val properties = properties(pageCountPerCohort = 3)
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val cursor = cursor(cohort, 5)
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor)
        `when`(seedService.seed(request(cohort, 5, properties))).thenReturn(
            seedResult(
                requestedPageCount = 3,
                discoveredPlayers = 10,
                uniquePlayers = 10,
                pagesProcessed = 1,
            ),
        )

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentTickOutcome.COMPLETED, result.outcome)
        assertEquals(6, result.attempts.single().nextPage)
        verify(cursorService).advance(cursor, 6)
    }

    @Test
    fun `wraps the cursor after an empty page instead of repeating that page`() {
        val properties = properties()
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val cursor = cursor(cohort, 8)
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor)
        `when`(seedService.seed(request(cohort, 8, properties))).thenReturn(seedResult(emptyPageEncountered = true))

        val result = service(properties).runOneTick()

        assertEquals(BenchmarkReplenishmentAttemptOutcome.EMPTY_PAGE_WRAPPED, result.attempts.single().outcome)
        verify(cursorService).advance(cursor, 1)
    }

    @Test
    fun `wraps the cursor when advancing the completed range would overflow`() {
        val properties = properties()
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val cursor = cursor(cohort, Int.MAX_VALUE)
        `when`(windowFactory.current()).thenReturn(window())
        stubCoverage(properties, window())
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor)
        `when`(seedService.seed(request(cohort, Int.MAX_VALUE, properties))).thenReturn(seedResult())

        service(properties).runOneTick()

        verify(cursorService).advance(cursor, 1)
    }

    @Test
    fun `rechecks coverage in a fresh window on the next tick and skips a healthy cohort`() {
        val properties = properties(cohorts = listOf("GOLD:I"))
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val firstWindow = window("2026-09-19T00:00:00Z")
        val secondWindow = window("2026-09-20T00:00:00Z")
        `when`(windowFactory.current()).thenReturn(firstWindow, secondWindow)
        `when`(coverageQueryService.findCoverage(cohort.coverageScope(), firstWindow)).thenReturn(emptyList())
        `when`(coverageQueryService.findCoverage(cohort.coverageScope(), secondWindow)).thenReturn(availableCoverage(cohort))
        `when`(cooldown.isBlocked()).thenReturn(false)
        `when`(cursorService.getOrCreate(cohort)).thenReturn(cursor(cohort, 1))
        `when`(seedService.seed(request(cohort, 1, properties))).thenReturn(seedResult())

        val firstResult = service(properties).runOneTick()
        val secondResult = service(properties).runOneTick()

        assertEquals(firstWindow, firstResult.queryWindow)
        assertTrue(firstResult.before.all { it.window == firstWindow })
        assertTrue(firstResult.after.all { it.window == firstWindow })
        assertEquals(secondWindow, secondResult.queryWindow)
        assertEquals(BenchmarkReplenishmentTickOutcome.HEALTHY, secondResult.outcome)
        verify(windowFactory, times(2)).current()
        verify(coverageQueryService, times(2)).findCoverage(cohort.coverageScope(), firstWindow)
        verify(coverageQueryService).findCoverage(cohort.coverageScope(), secondWindow)
        verify(seedService, times(1)).seed(request(cohort, 1, properties))
    }

    private fun service(properties: BenchmarkReplenishmentProperties) =
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

    private fun properties(
        maxCohortsPerTick: Int = 1,
        pageCountPerCohort: Int = 1,
        cohorts: List<String> = listOf("GOLD:I", "PLATINUM:I"),
    ): BenchmarkReplenishmentProperties =
        BenchmarkReplenishmentProperties(
            maxCohortsPerTick = maxCohortsPerTick,
            pageCountPerCohort = pageCountPerCohort,
            playerLimitPerCohort = 10,
            matchesPerPlayer = 5,
            cohorts = cohorts,
        )

    private fun window(toExclusive: String = "2026-09-19T00:00:00Z"): BenchmarkQueryWindow =
        BenchmarkQueryWindow(
            Instant.parse(toExclusive).minusSeconds(30 * 24 * 60 * 60),
            Instant.parse(toExclusive),
        )

    private fun stubCoverage(
        properties: BenchmarkReplenishmentProperties,
        window: BenchmarkQueryWindow,
    ) {
        properties.supportedCohorts().forEach { cohort ->
            `when`(coverageQueryService.findCoverage(cohort.coverageScope(), window)).thenReturn(emptyList())
        }
    }

    private fun availableCoverage(cohort: BenchmarkReplenishmentCohort): List<BenchmarkCohortCoverage> =
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

    private fun cursor(
        cohort: BenchmarkReplenishmentCohort,
        nextPage: Int,
    ) = BenchmarkReplenishmentCursor(cohort, nextPage, null)

    private fun request(
        cohort: BenchmarkReplenishmentCohort,
        startPage: Int,
        properties: BenchmarkReplenishmentProperties,
    ): BenchmarkSeedRequest =
        BenchmarkSeedRequest(
            tier = cohort.tier,
            division = cohort.division,
            startPage = startPage,
            pageCount = properties.pageCountPerCohort,
            playerLimit = properties.playerLimitPerCohort,
            matchesPerPlayer = properties.matchesPerPlayer,
            queryWindow = window(),
        )

    private fun seedResult(
        requestedPageCount: Int = 1,
        discoveredPlayers: Int = 0,
        uniquePlayers: Int = 0,
        candidatePlayers: Int = uniquePlayers,
        pagesProcessed: Int = 1,
        rateLimitStopped: Boolean = false,
        collectionResult: BenchmarkCollectionResult? = collectionResult(),
        retryAfterSeconds: Long? = null,
        emptyPageEncountered: Boolean = false,
    ): BenchmarkSeedResult =
        BenchmarkSeedResult(
            requestedStartPage = 1,
            requestedPageCount = requestedPageCount,
            discoveredPlayers = discoveredPlayers,
            candidatePlayers = candidatePlayers,
            uniquePlayers = uniquePlayers,
            selectedZeroValidSamplePlayers = uniquePlayers,
            selectedExistingValidSamplePlayers = 0,
            pagesProcessed = pagesProcessed,
            collectionResult = collectionResult,
            rateLimitStopped = rateLimitStopped,
            retryAfterSeconds = retryAfterSeconds,
            emptyPageEncountered = emptyPageEncountered,
        )

    private fun collectionResult(
        createdSamples: Int = 0,
        skippedDuplicates: Int = 0,
        skippedInvalidSamples: Int = 0,
        rateLimitStopped: Boolean = false,
        retryAfterSeconds: Long? = null,
    ): BenchmarkCollectionResult =
        BenchmarkCollectionResult(
            inputPlayers = 0,
            playersProcessed = 0,
            playerMatchListFailures = 0,
            discoveredMatchIds = 0,
            uniqueMatchIds = 0,
            fetchedMatches = 0,
            failedMatches = 0,
            createdSamples = createdSamples,
            skippedDuplicates = skippedDuplicates,
            skippedInvalidSamples = skippedInvalidSamples,
            rateLimitStopped = rateLimitStopped,
            retryAfterSeconds = retryAfterSeconds,
        )
}
