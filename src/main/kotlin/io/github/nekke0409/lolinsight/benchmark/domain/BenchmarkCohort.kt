package io.github.nekke0409.lolinsight.benchmark.domain

data class BenchmarkCohort(
    val scope: BenchmarkScope,
    val region: String,
    val queueId: Int,
    val tier: String,
    val division: String,
    val position: String,
    val championId: Int?,
) {
    init {
        require(region.isNotBlank()) { "region must not be blank" }
        require(queueId > 0) { "queueId must be positive" }
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }
        require(position.isNotBlank()) { "position must not be blank" }
        when (scope) {
            BenchmarkScope.POSITION -> require(championId == null) { "POSITION scope must not include championId" }
            BenchmarkScope.CHAMPION_POSITION ->
                require(championId != null && championId > 0) {
                    "CHAMPION_POSITION scope requires a positive championId"
                }
        }
    }

    companion object {
        fun position(
            region: String,
            queueId: Int,
            tier: String,
            division: String,
            position: String,
        ): BenchmarkCohort =
            BenchmarkCohort(
                scope = BenchmarkScope.POSITION,
                region = region,
                queueId = queueId,
                tier = tier,
                division = division,
                position = position,
                championId = null,
            )

        fun championPosition(
            region: String,
            queueId: Int,
            tier: String,
            division: String,
            position: String,
            championId: Int,
        ): BenchmarkCohort =
            BenchmarkCohort(
                scope = BenchmarkScope.CHAMPION_POSITION,
                region = region,
                queueId = queueId,
                tier = tier,
                division = division,
                position = position,
                championId = championId,
            )
    }
}
