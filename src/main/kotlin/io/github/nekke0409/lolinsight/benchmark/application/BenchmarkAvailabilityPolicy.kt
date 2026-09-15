package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import org.springframework.stereotype.Component

@Component
class BenchmarkAvailabilityPolicy(
    private val properties: BenchmarkAvailabilityProperties,
) {
    fun determine(
        sampleCount: Long,
        uniquePlayerCount: Long,
    ): BenchmarkAvailability {
        require(sampleCount >= 0) { "sampleCount cannot be negative" }
        require(uniquePlayerCount >= 0) { "uniquePlayerCount cannot be negative" }
        require(uniquePlayerCount <= sampleCount) { "uniquePlayerCount cannot exceed sampleCount" }

        if (sampleCount == 0L) {
            return BenchmarkAvailability.NO_DATA
        }

        return if (
            sampleCount >= properties.minimumSampleCount &&
            uniquePlayerCount >= properties.minimumUniquePlayerCount
        ) {
            BenchmarkAvailability.AVAILABLE
        } else {
            BenchmarkAvailability.INSUFFICIENT_SAMPLE
        }
    }

    fun samplesNeeded(sampleCount: Long): Long {
        require(sampleCount >= 0) { "sampleCount cannot be negative" }

        return (properties.minimumSampleCount - sampleCount).coerceAtLeast(0)
    }

    fun uniquePlayersNeeded(uniquePlayerCount: Long): Long {
        require(uniquePlayerCount >= 0) { "uniquePlayerCount cannot be negative" }

        return (properties.minimumUniquePlayerCount - uniquePlayerCount).coerceAtLeast(0)
    }
}
