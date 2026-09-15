package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort

data class BenchmarkCohortCoverageRow(
    val cohort: BenchmarkCohort,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
)
