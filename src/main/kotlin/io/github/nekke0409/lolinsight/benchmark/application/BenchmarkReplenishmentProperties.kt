package io.github.nekke0409.lolinsight.benchmark.application

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("benchmark.replenishment")
data class BenchmarkReplenishmentProperties(
    val enabled: Boolean = false,
    val interval: Duration = Duration.ofHours(24),
    @field:Min(1)
    @field:Max(10)
    val maxCohortsPerTick: Int = 1,
    @field:Min(1)
    @field:Max(10)
    val pageCountPerCohort: Int = 1,
    @field:Min(1)
    @field:Max(100)
    val playerLimitPerCohort: Int = 10,
    @field:Min(1)
    @field:Max(100)
    val matchesPerPlayer: Int = 5,
    val cohorts: List<String> = listOf("GOLD:I"),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "benchmark replenishment interval must be positive" }
        require(cohorts.isNotEmpty()) { "benchmark replenishment cohorts must not be empty" }
        cohorts.forEach(::parseCohort)
    }

    fun supportedCohorts(): List<BenchmarkReplenishmentCohort> =
        cohorts
            .map(::parseCohort)
            .distinct()
            .sortedWith(compareBy(BenchmarkReplenishmentCohort::tier).thenBy(BenchmarkReplenishmentCohort::division))

    private fun parseCohort(value: String): BenchmarkReplenishmentCohort {
        val normalized = value.trim().uppercase()
        val separator = normalized.indexOf(':')
        require(separator in 1 until normalized.lastIndex) {
            "BENCHMARK_REPLENISHMENT_COHORTS entries must be TIER:DIVISION"
        }
        require(normalized.indexOf(':', separator + 1) == -1) {
            "BENCHMARK_REPLENISHMENT_COHORTS entries must be TIER:DIVISION"
        }
        return BenchmarkReplenishmentCohort(
            tier = normalized.substring(0, separator).trim(),
            division = normalized.substring(separator + 1).trim(),
        )
    }
}
