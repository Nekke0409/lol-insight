package io.github.nekke0409.lolinsight.benchmark.domain

data class BenchmarkMetricDistribution(
    val mean: Double,
    val median: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
) {
    init {
        require(listOf(mean, median, p25, p75, p90).all(Double::isFinite)) {
            "Benchmark distribution values must be finite"
        }
    }
}
