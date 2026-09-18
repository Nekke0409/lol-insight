package io.github.nekke0409.lolinsight.automation.persistence

import io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tracked_player_automation")
class TrackedPlayerAutomationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 100)
    var puuid: String = "",
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(name = "last_seen_match_id", length = 100)
    var lastSeenMatchId: String? = null,
    @Column(name = "last_checked_at")
    var lastCheckedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    fun toDomain(): TrackedPlayerAutomation =
        TrackedPlayerAutomation(
            id = id,
            puuid = puuid,
            enabled = enabled,
            lastSeenMatchId = lastSeenMatchId,
            lastCheckedAt = lastCheckedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
