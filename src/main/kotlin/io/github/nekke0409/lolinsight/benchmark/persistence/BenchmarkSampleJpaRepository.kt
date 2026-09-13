package io.github.nekke0409.lolinsight.benchmark.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BenchmarkSampleJpaRepository : JpaRepository<BenchmarkSampleEntity, Long> {
    fun findByMatchIdAndPuuid(
        matchId: String,
        puuid: String,
    ): BenchmarkSampleEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            """
            INSERT INTO benchmark_sample (
                match_id, puuid, region, queue_id, tier, division, rank_captured_at,
                champion_id, position, game_version, game_start_timestamp,
                kills, deaths, assists, kda, cs_per_minute, gold_per_minute,
                damage_per_minute, vision_per_minute, kill_participation, damage_share, collected_at
            ) VALUES (
                :#{#sample.matchId}, :#{#sample.puuid}, :#{#sample.region}, :#{#sample.queueId},
                :#{#sample.tier}, :#{#sample.division}, :#{#sample.rankCapturedAt},
                :#{#sample.championId}, :#{#sample.position}, :#{#sample.gameVersion},
                :#{#sample.gameStartTimestamp}, :#{#sample.kills}, :#{#sample.deaths},
                :#{#sample.assists}, :#{#sample.kda}, :#{#sample.csPerMinute},
                :#{#sample.goldPerMinute}, :#{#sample.damagePerMinute}, :#{#sample.visionPerMinute},
                :#{#sample.killParticipation}, :#{#sample.damageShare}, :#{#sample.collectedAt}
            ) ON CONFLICT ON CONSTRAINT uk_benchmark_sample_match_puuid DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("sample") sample: BenchmarkSampleEntity,
    ): Int
}
