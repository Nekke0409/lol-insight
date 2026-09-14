package io.github.nekke0409.lolinsight.benchmark.application

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("benchmark.availability")
data class BenchmarkAvailabilityProperties(
    @field:Positive
    val minimumSampleCount: Long = 30,
    @field:Positive
    val minimumUniquePlayerCount: Long = 10,
)
