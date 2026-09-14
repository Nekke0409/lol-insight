package io.github.nekke0409.lolinsight.benchmark.domain

import java.time.Instant

data class SampledRankedPlayer(
    val puuid: String,
    val region: String,
    val queue: String,
    val tier: String,
    val division: String,
    val rankCapturedAt: Instant,
) {
    init {
        require(puuid.isNotBlank()) { "puuid must not be blank" }
        require(region.isNotBlank()) { "region must not be blank" }
        require(queue.isNotBlank()) { "queue must not be blank" }
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
    }
}
