package io.github.nekke0409.lolinsight.benchmark.domain

data class BenchmarkCohort(
    val region: String,
    val queueId: Int,
    val tier: String,
    val division: String,
    val position: String,
    val championId: Int,
) {
    init {
        require(region.isNotBlank()) { "region must not be blank" }
        require(queueId > 0) { "queueId must be positive" }
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(position.isNotBlank()) { "position must not be blank" }
        require(championId > 0) { "championId must be positive" }
    }
}
