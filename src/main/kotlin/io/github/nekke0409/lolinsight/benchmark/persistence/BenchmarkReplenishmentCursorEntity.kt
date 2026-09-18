package io.github.nekke0409.lolinsight.benchmark.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "benchmark_replenishment_cursor")
class BenchmarkReplenishmentCursorEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, length = 20)
    var region: String = "",
    @Column(name = "queue_id", nullable = false)
    var queueId: Int = 0,
    @Column(nullable = false, length = 20)
    var tier: String = "",
    @Column(nullable = false, length = 10)
    var division: String = "",
    @Column(name = "next_page", nullable = false)
    var nextPage: Int = 1,
    @Column(name = "last_attempted_at")
    var lastAttemptedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
