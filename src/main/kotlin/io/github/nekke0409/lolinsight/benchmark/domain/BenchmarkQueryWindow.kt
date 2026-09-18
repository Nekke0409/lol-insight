package io.github.nekke0409.lolinsight.benchmark.domain

import java.time.Instant

data class BenchmarkQueryWindow(
    val fromInclusive: Instant,
    val toExclusive: Instant,
) {
    init {
        require(fromInclusive < toExclusive) { "fromInclusive must be before toExclusive" }
    }
}
