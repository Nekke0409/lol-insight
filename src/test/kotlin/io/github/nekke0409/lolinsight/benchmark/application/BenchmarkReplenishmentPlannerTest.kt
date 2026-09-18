package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkReplenishmentPlannerTest {
    private val policy = BenchmarkAvailabilityPolicy(BenchmarkAvailabilityProperties())
    private val planner = BenchmarkReplenishmentPlanner(policy)
    private val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
    private val window = BenchmarkQueryWindow(Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-09-19T00:00:00Z"))

    @Test
    fun `marks a cohort healthy only when every POSITION is available`() {
        val plan = planner.plan(cohort, window, BenchmarkReplenishmentPlanner.POSITIONS.map { coverage(it, 30, 10) })

        assertEquals(BenchmarkReplenishmentStatus.HEALTHY, plan.status)
        assertTrue(plan.insufficientPositions.isEmpty())
    }

    @Test
    fun `plans replenishment when one POSITION is insufficient`() {
        val plan =
            planner.plan(
                cohort,
                window,
                BenchmarkReplenishmentPlanner.POSITIONS.map { position ->
                    if (position == "MIDDLE") coverage(position, 29, 10) else coverage(position, 30, 10)
                },
            )

        assertEquals(BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT, plan.status)
        val middle = plan.insufficientPositions.single()
        assertEquals("MIDDLE", middle.cohort.position)
        assertEquals(BenchmarkAvailability.INSUFFICIENT_SAMPLE, middle.availability)
        assertEquals(1, middle.samplesNeeded)
    }

    @Test
    fun `treats missing POSITION rows as no data`() {
        val plan = planner.plan(cohort, window, emptyList())

        assertEquals(BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT, plan.status)
        assertEquals(5, plan.insufficientPositions.size)
        assertTrue(plan.insufficientPositions.all { it.availability == BenchmarkAvailability.NO_DATA })
        assertTrue(plan.insufficientPositions.all { it.samplesNeeded == 30L && it.uniquePlayersNeeded == 10L })
    }

    @Test
    fun `does not use champion-position coverage as a replenishment trigger`() {
        val championCoverage =
            BenchmarkCohortCoverage(
                cohort = BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", "MIDDLE", 103),
                sampleCount = 1,
                uniquePlayerCount = 1,
                availability = BenchmarkAvailability.INSUFFICIENT_SAMPLE,
                samplesNeeded = 29,
                uniquePlayersNeeded = 9,
            )

        val plan =
            planner.plan(
                cohort,
                window,
                BenchmarkReplenishmentPlanner.POSITIONS.map { coverage(it, 30, 10) } + championCoverage,
            )

        assertEquals(BenchmarkReplenishmentStatus.HEALTHY, plan.status)
    }

    @Test
    fun `prioritizes no data then unavailable position count then deficit with a stable cohort tie break`() {
        val gold = planner.plan(cohort, window, listOf(coverage("TOP", 30, 10)))
        val platinum =
            planner.plan(
                BenchmarkReplenishmentCohort("PLATINUM", "I"),
                window,
                listOf(coverage("TOP", 1, 1, tier = "PLATINUM")),
            )
        val goldTwo =
            planner.plan(
                BenchmarkReplenishmentCohort("GOLD", "II"),
                window,
                BenchmarkReplenishmentPlanner.POSITIONS.map { coverage(it, 29, 9, division = "II") },
            )

        assertEquals(
            listOf(platinum.cohort, gold.cohort, goldTwo.cohort),
            planner.prioritize(listOf(goldTwo, platinum, gold)).map { it.cohort },
        )
    }

    private fun coverage(
        position: String,
        samples: Long,
        players: Long,
        tier: String = "GOLD",
        division: String = "I",
    ): BenchmarkCohortCoverage =
        BenchmarkCohortCoverage(
            cohort =
                BenchmarkCohort(
                    scope = BenchmarkScope.POSITION,
                    region = "KR",
                    queueId = 420,
                    tier = tier,
                    division = division,
                    position = position,
                    championId = null,
                ),
            sampleCount = samples,
            uniquePlayerCount = players,
            availability = policy.determine(samples, players),
            samplesNeeded = policy.samplesNeeded(samples),
            uniquePlayersNeeded = policy.uniquePlayersNeeded(players),
        )
}
