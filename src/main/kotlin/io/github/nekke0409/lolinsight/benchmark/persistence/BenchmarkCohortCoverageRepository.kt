package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class BenchmarkCohortCoverageRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun findCoverage(scope: BenchmarkCohortCoverageScope): List<BenchmarkCohortCoverageRow> =
        jdbcTemplate.query(
            COVERAGE_SQL,
            MapSqlParameterSource(
                mapOf(
                    "region" to scope.region,
                    "queueId" to scope.queueId,
                    "tier" to scope.tier,
                    "division" to scope.division,
                ),
            ),
        ) { resultSet, _ ->
            BenchmarkCohortCoverageRow(
                cohort =
                    BenchmarkCohort(
                        scope = BenchmarkScope.valueOf(resultSet.getString("benchmark_scope")),
                        region = resultSet.getString("region"),
                        queueId = resultSet.getInt("queue_id"),
                        tier = resultSet.getString("tier"),
                        division = resultSet.getString("division"),
                        position = resultSet.getString("position"),
                        championId = resultSet.getInt("champion_id").takeUnless { resultSet.wasNull() },
                    ),
                sampleCount = resultSet.getLong("sample_count"),
                uniquePlayerCount = resultSet.getLong("unique_player_count"),
            )
        }

    private companion object {
        val COVERAGE_SQL =
            """
            SELECT
                'POSITION' AS benchmark_scope,
                region,
                queue_id,
                tier,
                division,
                position,
                CAST(NULL AS INTEGER) AS champion_id,
                COUNT(*) AS sample_count,
                COUNT(DISTINCT puuid) AS unique_player_count
            FROM benchmark_sample
            WHERE region = :region
              AND queue_id = :queueId
              AND tier = :tier
              AND division = :division
            GROUP BY region, queue_id, tier, division, position

            UNION ALL

            SELECT
                'CHAMPION_POSITION' AS benchmark_scope,
                region,
                queue_id,
                tier,
                division,
                position,
                champion_id,
                COUNT(*) AS sample_count,
                COUNT(DISTINCT puuid) AS unique_player_count
            FROM benchmark_sample
            WHERE region = :region
              AND queue_id = :queueId
              AND tier = :tier
              AND division = :division
            GROUP BY region, queue_id, tier, division, position, champion_id
            """.trimIndent()
    }
}
