package io.github.nekke0409.lolinsight.benchmark.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "benchmark_sample")
class BenchmarkSampleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "match_id", nullable = false, length = 100)
    var matchId: String = "",
    @Column(nullable = false, length = 100)
    var puuid: String = "",
    @Column(nullable = false, length = 20)
    var region: String = "",
    @Column(name = "queue_id", nullable = false)
    var queueId: Int = 0,
    @Column(nullable = false, length = 20)
    var tier: String = "",
    @Column(nullable = false, length = 10)
    var division: String = "",
    @Column(name = "rank_captured_at", nullable = false)
    var rankCapturedAt: Instant = Instant.EPOCH,
    @Column(name = "champion_id", nullable = false)
    var championId: Int = 0,
    @Column(nullable = false, length = 20)
    var position: String = "",
    @Column(name = "game_version", nullable = false, length = 30)
    var gameVersion: String = "",
    @Column(name = "game_start_timestamp", nullable = false)
    var gameStartTimestamp: Instant = Instant.EPOCH,
    @Column(nullable = false)
    var kills: Int = 0,
    @Column(nullable = false)
    var deaths: Int = 0,
    @Column(nullable = false)
    var assists: Int = 0,
    @Column(nullable = false)
    var kda: Double = 0.0,
    @Column(name = "cs_per_minute", nullable = false)
    var csPerMinute: Double = 0.0,
    @Column(name = "gold_per_minute", nullable = false)
    var goldPerMinute: Double = 0.0,
    @Column(name = "damage_per_minute", nullable = false)
    var damagePerMinute: Double = 0.0,
    @Column(name = "vision_per_minute", nullable = false)
    var visionPerMinute: Double = 0.0,
    @Column(name = "kill_participation", nullable = false)
    var killParticipation: Double = 0.0,
    @Column(name = "damage_share", nullable = false)
    var damageShare: Double = 0.0,
    @Column(name = "collected_at", nullable = false)
    var collectedAt: Instant = Instant.EPOCH,
)
