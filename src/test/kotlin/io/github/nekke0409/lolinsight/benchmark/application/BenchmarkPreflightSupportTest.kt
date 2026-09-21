package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonAvailabilityPolicy
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkPreflightSupportTest {
    private val availabilityPolicy = PlayerComparisonAvailabilityPolicy()

    @Test
    fun `validates Gold and Emerald settings through the same configuration path`() {
        val gold = settings("GOLD", "I", "TOP")
        val emerald = settings("EMERALD", "IV", "TOP")

        assertEquals(BenchmarkReplenishmentCohort("GOLD", "I"), gold.expectedCohort)
        assertEquals(BenchmarkReplenishmentCohort("EMERALD", "IV"), emerald.expectedCohort)
        assertEquals("TOP", gold.position)
        assertEquals("TOP", emerald.position)
    }

    @Test
    fun `fails configuration before a lookup when a required value is missing or unsupported`() {
        assertFailsWith<IllegalArgumentException> { BenchmarkPreflightSettings.from { null } }
        assertFailsWith<IllegalArgumentException> { BenchmarkPreflightSettings.from { name -> values("GOLD", "I", "ADC")[name] } }
        assertFailsWith<IllegalArgumentException> { BenchmarkPreflightSettings.from { name -> values("MASTER", "I", "TOP")[name] } }
    }

    @Test
    fun `reports a rank mismatch and unranked target as not ready`() {
        val mismatch = assess(PlayerRankContext("GOLD", "I", CAPTURED_AT), 8, BenchmarkAvailability.AVAILABLE)
        val unranked = assess(null, 8, BenchmarkAvailability.AVAILABLE)

        assertFalse(mismatch.ready)
        assertEquals("current Solo rank does not match the expected cohort", mismatch.reason)
        assertFalse(unranked.ready)
        assertEquals("current Solo rank is UNRANKED", unranked.reason)
    }

    @Test
    fun `reports independent user sample and self-excluded benchmark availability failures`() {
        val insufficientUser = assess(EXPECTED_RANK, 4, BenchmarkAvailability.AVAILABLE)
        val insufficientBenchmark = assess(EXPECTED_RANK, 5, BenchmarkAvailability.INSUFFICIENT_SAMPLE)
        val noBenchmark = assess(EXPECTED_RANK, 5, BenchmarkAvailability.NO_DATA)

        assertEquals(PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE, insufficientUser.comparisonStatus)
        assertEquals("target position has insufficient Ranked Solo games", insufficientUser.reason)
        assertEquals(PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE, insufficientBenchmark.comparisonStatus)
        assertEquals("self-excluded benchmark is below the availability threshold", insufficientBenchmark.reason)
        assertEquals(PlayerCohortComparisonStatus.BENCHMARK_NO_DATA, noBenchmark.comparisonStatus)
        assertEquals("self-excluded benchmark has no valid samples", noBenchmark.reason)
    }

    @Test
    fun `is ready only when rank user sample and self-excluded benchmark all pass`() {
        val assessment = assess(EXPECTED_RANK, 5, BenchmarkAvailability.AVAILABLE)

        assertTrue(assessment.rankMatchesExpectedCohort)
        assertTrue(assessment.ready)
        assertEquals("ready", assessment.reason)
    }

    private fun settings(
        tier: String,
        division: String,
        position: String,
    ): BenchmarkPreflightSettings = BenchmarkPreflightSettings.from { name -> values(tier, division, position)[name] }

    private fun values(
        tier: String,
        division: String,
        position: String,
    ): Map<String, String> =
        mapOf(
            "TARGET_GAME_NAME" to "target-name",
            "TARGET_TAG_LINE" to "KR1",
            "RIOT_API_KEY" to "test-key",
            "BENCHMARK_PREFLIGHT_EXPECTED_TIER" to tier,
            "BENCHMARK_PREFLIGHT_EXPECTED_DIVISION" to division,
            "BENCHMARK_PREFLIGHT_POSITION" to position,
        )

    private fun assess(
        rank: PlayerRankContext?,
        games: Int,
        availability: BenchmarkAvailability,
    ): BenchmarkPreflightAssessment =
        BenchmarkPreflightAssessment.assess(
            actualRank = rank,
            expectedCohort = BenchmarkReplenishmentCohort("EMERALD", "IV"),
            userPositionGames = games,
            selfExcludedAvailability = availability,
            comparisonAvailabilityPolicy = availabilityPolicy,
        )

    private companion object {
        val CAPTURED_AT: Instant = Instant.parse("2026-09-21T00:00:00Z")
        val EXPECTED_RANK = PlayerRankContext("EMERALD", "IV", CAPTURED_AT)
    }
}
