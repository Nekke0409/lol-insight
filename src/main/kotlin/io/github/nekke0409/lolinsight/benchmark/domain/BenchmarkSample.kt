package io.github.nekke0409.lolinsight.benchmark.domain

import java.time.Instant

data class BenchmarkSample(
    val id: Long? = null,
    val matchId: String,
    val puuid: String,
    val region: String,
    val queueId: Int,
    val tier: String,
    val division: String,
    val rankCapturedAt: Instant,
    val championId: Int,
    val position: String,
    val gameVersion: String,
    val gameStartTimestamp: Instant,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val kda: Double,
    val csPerMinute: Double,
    val goldPerMinute: Double,
    val damagePerMinute: Double,
    val visionPerMinute: Double,
    val killParticipation: Double,
    val damageShare: Double,
    val collectedAt: Instant,
) {
    init {
        require(matchId.isNotBlank()) { "matchId must not be blank" }
        require(puuid.isNotBlank()) { "puuid must not be blank" }
        require(
            listOf(
                kda,
                csPerMinute,
                goldPerMinute,
                damagePerMinute,
                visionPerMinute,
                killParticipation,
                damageShare,
            ).all(Double::isFinite),
        ) { "Benchmark metrics must be finite" }
    }
}
