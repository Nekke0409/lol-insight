package io.github.nekke0409.lolinsight.automation.domain

import java.time.Instant
import java.util.UUID

data class TrackedPlayerAutomation(
    val id: UUID,
    val puuid: String,
    val enabled: Boolean,
    val lastSeenMatchId: String?,
    val lastCheckedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
