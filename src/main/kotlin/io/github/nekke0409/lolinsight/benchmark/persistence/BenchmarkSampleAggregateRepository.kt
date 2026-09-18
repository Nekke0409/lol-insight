package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmark
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.ZoneOffset

@Repository
class BenchmarkSampleAggregateRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun findBenchmark(
        cohort: BenchmarkCohort,
        window: BenchmarkQueryWindow,
    ): PeerBenchmark? = queryBenchmark(cohort, aggregateSql(cohort), aggregateParameters(cohort, window))

    fun findBenchmarkExcludingPlayer(
        cohort: BenchmarkCohort,
        excludedPuuid: String,
        window: BenchmarkQueryWindow,
    ): PeerBenchmark? =
        queryBenchmark(
            cohort = cohort,
            sql = "${aggregateSql(cohort)}\n  AND puuid <> :excludedPuuid",
            parameters = aggregateParameters(cohort, window).addValue("excludedPuuid", excludedPuuid),
        )

    private fun queryBenchmark(
        cohort: BenchmarkCohort,
        sql: String,
        parameters: MapSqlParameterSource,
    ): PeerBenchmark? =
        jdbcTemplate.query(
            sql,
            parameters,
            ResultSetExtractor { resultSet -> resultSet.toPeerBenchmark(cohort) },
        )

    private fun aggregateParameters(
        cohort: BenchmarkCohort,
        window: BenchmarkQueryWindow,
    ): MapSqlParameterSource =
        MapSqlParameterSource(
            mapOf(
                "region" to cohort.region,
                "queueId" to cohort.queueId,
                "tier" to cohort.tier,
                "division" to cohort.division,
                "position" to cohort.position,
                "championId" to cohort.championId,
            ),
        ).addValue(
            "fromInclusive",
            window.fromInclusive.atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE,
        ).addValue(
            "toExclusive",
            window.toExclusive.atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE,
        )

    private fun ResultSet.toPeerBenchmark(cohort: BenchmarkCohort): PeerBenchmark? {
        if (!next() || getLong("sample_count") == 0L) {
            return null
        }

        return PeerBenchmark(
            cohort = cohort,
            sampleCount = getLong("sample_count"),
            uniquePlayerCount = getLong("unique_player_count"),
            kda =
                BenchmarkMetricDistribution(
                    mean = getDouble("kda_mean"),
                    median = getDouble("kda_median"),
                    p25 = getDouble("kda_p25"),
                    p75 = getDouble("kda_p75"),
                    p90 = getDouble("kda_p90"),
                ),
            csPerMinute =
                BenchmarkMetricDistribution(
                    mean = getDouble("cs_per_minute_mean"),
                    median = getDouble("cs_per_minute_median"),
                    p25 = getDouble("cs_per_minute_p25"),
                    p75 = getDouble("cs_per_minute_p75"),
                    p90 = getDouble("cs_per_minute_p90"),
                ),
            goldPerMinute =
                BenchmarkMetricDistribution(
                    mean = getDouble("gold_per_minute_mean"),
                    median = getDouble("gold_per_minute_median"),
                    p25 = getDouble("gold_per_minute_p25"),
                    p75 = getDouble("gold_per_minute_p75"),
                    p90 = getDouble("gold_per_minute_p90"),
                ),
            damagePerMinute =
                BenchmarkMetricDistribution(
                    mean = getDouble("damage_per_minute_mean"),
                    median = getDouble("damage_per_minute_median"),
                    p25 = getDouble("damage_per_minute_p25"),
                    p75 = getDouble("damage_per_minute_p75"),
                    p90 = getDouble("damage_per_minute_p90"),
                ),
            visionPerMinute =
                BenchmarkMetricDistribution(
                    mean = getDouble("vision_per_minute_mean"),
                    median = getDouble("vision_per_minute_median"),
                    p25 = getDouble("vision_per_minute_p25"),
                    p75 = getDouble("vision_per_minute_p75"),
                    p90 = getDouble("vision_per_minute_p90"),
                ),
            killParticipation =
                BenchmarkMetricDistribution(
                    mean = getDouble("kill_participation_mean"),
                    median = getDouble("kill_participation_median"),
                    p25 = getDouble("kill_participation_p25"),
                    p75 = getDouble("kill_participation_p75"),
                    p90 = getDouble("kill_participation_p90"),
                ),
            damageShare =
                BenchmarkMetricDistribution(
                    mean = getDouble("damage_share_mean"),
                    median = getDouble("damage_share_median"),
                    p25 = getDouble("damage_share_p25"),
                    p75 = getDouble("damage_share_p75"),
                    p90 = getDouble("damage_share_p90"),
                ),
        )
    }

    private fun aggregateSql(cohort: BenchmarkCohort): String =
        when (cohort.scope) {
            BenchmarkScope.POSITION -> POSITION_AGGREGATE_SQL
            BenchmarkScope.CHAMPION_POSITION -> CHAMPION_POSITION_AGGREGATE_SQL
        }

    private companion object {
        val AGGREGATE_SELECT =
            """
            SELECT
                COUNT(*) AS sample_count,
                COUNT(DISTINCT puuid) AS unique_player_count,
                AVG(kda) AS kda_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY kda) AS kda_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY kda) AS kda_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY kda) AS kda_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY kda) AS kda_p90,
                AVG(cs_per_minute) AS cs_per_minute_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY cs_per_minute) AS cs_per_minute_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY cs_per_minute) AS cs_per_minute_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY cs_per_minute) AS cs_per_minute_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY cs_per_minute) AS cs_per_minute_p90,
                AVG(gold_per_minute) AS gold_per_minute_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY gold_per_minute) AS gold_per_minute_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY gold_per_minute) AS gold_per_minute_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY gold_per_minute) AS gold_per_minute_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY gold_per_minute) AS gold_per_minute_p90,
                AVG(damage_per_minute) AS damage_per_minute_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY damage_per_minute) AS damage_per_minute_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY damage_per_minute) AS damage_per_minute_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY damage_per_minute) AS damage_per_minute_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY damage_per_minute) AS damage_per_minute_p90,
                AVG(vision_per_minute) AS vision_per_minute_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY vision_per_minute) AS vision_per_minute_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY vision_per_minute) AS vision_per_minute_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY vision_per_minute) AS vision_per_minute_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY vision_per_minute) AS vision_per_minute_p90,
                AVG(kill_participation) AS kill_participation_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY kill_participation) AS kill_participation_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY kill_participation) AS kill_participation_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY kill_participation) AS kill_participation_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY kill_participation) AS kill_participation_p90,
                AVG(damage_share) AS damage_share_mean,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY damage_share) AS damage_share_median,
                percentile_cont(0.25) WITHIN GROUP (ORDER BY damage_share) AS damage_share_p25,
                percentile_cont(0.75) WITHIN GROUP (ORDER BY damage_share) AS damage_share_p75,
                percentile_cont(0.9) WITHIN GROUP (ORDER BY damage_share) AS damage_share_p90
            FROM benchmark_sample
            """.trimIndent()

        val POSITION_AGGREGATE_SQL =
            """
            $AGGREGATE_SELECT
            WHERE region = :region
              AND queue_id = :queueId
              AND tier = :tier
              AND division = :division
              AND position = :position
              AND game_start_timestamp >= :fromInclusive
              AND game_start_timestamp < :toExclusive
            """.trimIndent()

        val CHAMPION_POSITION_AGGREGATE_SQL =
            """
            $POSITION_AGGREGATE_SQL
              AND champion_id = :championId
            """.trimIndent()
    }
}
