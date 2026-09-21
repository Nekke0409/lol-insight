package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.ZoneOffset

/** Reads current valid sample counts for one bounded discovery candidate set in a single query. */
@Repository
class BenchmarkSampleValidityRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun findValidSampleCounts(
        candidatePuuids: Collection<String>,
        region: String,
        queueId: Int,
        tier: String,
        division: String,
        window: BenchmarkQueryWindow,
    ): Map<String, Long> {
        require(region.isNotBlank()) { "region must not be blank" }
        require(queueId > 0) { "queueId must be positive" }
        require(tier.isNotBlank()) { "tier must not be blank" }
        require(division.isNotBlank()) { "division must not be blank" }

        val distinctPuuids = candidatePuuids.toSortedSet()
        require(distinctPuuids.none(String::isBlank)) { "candidatePuuids cannot contain blank values" }
        if (distinctPuuids.isEmpty()) {
            return emptyMap()
        }

        val counts =
            jdbcTemplate
                .query(
                    VALID_SAMPLE_COUNT_SQL,
                    MapSqlParameterSource(
                        mapOf(
                            "candidatePuuids" to distinctPuuids,
                            "region" to region,
                            "queueId" to queueId,
                            "tier" to tier,
                            "division" to division,
                        ),
                    ).addValue(
                        "fromInclusive",
                        window.fromInclusive.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE,
                    ).addValue(
                        "toExclusive",
                        window.toExclusive.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE,
                    ),
                ) { resultSet, _ ->
                    resultSet.getString("puuid") to resultSet.getLong("valid_sample_count")
                }.toMap()

        return distinctPuuids.associateWith { puuid -> counts[puuid] ?: 0L }
    }

    private companion object {
        val VALID_SAMPLE_COUNT_SQL =
            """
            SELECT puuid, COUNT(*) AS valid_sample_count
            FROM benchmark_sample
            WHERE region = :region
              AND queue_id = :queueId
              AND tier = :tier
              AND division = :division
              AND puuid IN (:candidatePuuids)
              AND game_start_timestamp >= :fromInclusive
              AND game_start_timestamp < :toExclusive
            GROUP BY puuid
            """.trimIndent()
    }
}
