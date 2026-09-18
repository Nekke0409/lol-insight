package io.github.nekke0409.lolinsight.benchmark.application

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** Keeps replenishment telemetry free of cohort identifiers and Riot/player identifiers. */
@Component
class BenchmarkReplenishmentObservationRecorder(
    private val meterRegistry: MeterRegistry,
) {
    fun tick(outcome: BenchmarkReplenishmentTickOutcome) {
        meterRegistry.counter("benchmark.replenishment.ticks", "outcome", outcome.metricValue).increment()
    }

    fun cohort(outcome: String) {
        meterRegistry.counter("benchmark.replenishment.cohorts", "outcome", outcome).increment()
    }

    fun seed(outcome: String) {
        meterRegistry.counter("benchmark.replenishment.seeds", "outcome", outcome).increment()
    }

    fun rateLimitStopped() {
        meterRegistry.counter("benchmark.replenishment.rate_limit_stops").increment()
    }
}
