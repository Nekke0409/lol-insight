package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation
import io.github.nekke0409.lolinsight.automation.persistence.TrackedPlayerAutomationEntity
import io.github.nekke0409.lolinsight.automation.persistence.TrackedPlayerAutomationJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TrackedPlayerAutomationService(
    private val trackedPlayerAutomationJpaRepository: TrackedPlayerAutomationJpaRepository,
) {
    @Transactional
    fun createIfAbsent(
        puuid: String,
        initialCursor: String?,
        checkedAt: Instant,
    ): TrackedPlayerAutomation {
        trackedPlayerAutomationJpaRepository.findByPuuid(puuid)?.let { return it.toDomain() }

        return trackedPlayerAutomationJpaRepository
            .saveAndFlush(
                TrackedPlayerAutomationEntity(
                    puuid = puuid,
                    enabled = true,
                    lastSeenMatchId = initialCursor,
                    lastCheckedAt = checkedAt,
                    createdAt = checkedAt,
                    updatedAt = checkedAt,
                ),
            ).toDomain()
    }

    @Transactional(readOnly = true)
    fun findEnabled(automationId: UUID): TrackedPlayerAutomation? =
        trackedPlayerAutomationJpaRepository.findByIdAndEnabledTrue(automationId)?.toDomain()

    @Transactional(readOnly = true)
    fun findDue(
        dueBefore: Instant,
        batchSize: Int,
    ): List<TrackedPlayerAutomation> =
        trackedPlayerAutomationJpaRepository
            .findDue(dueBefore, PageRequest.of(0, batchSize))
            .map(TrackedPlayerAutomationEntity::toDomain)

    @Transactional
    fun initializeCursorIfUninitialized(
        automationId: UUID,
        lastSeenMatchId: String?,
        checkedAt: Instant,
    ): Boolean =
        trackedPlayerAutomationJpaRepository.initializeCursorIfUninitialized(
            automationId = automationId,
            lastSeenMatchId = lastSeenMatchId,
            checkedAt = checkedAt,
            updatedAt = checkedAt,
        ) == 1

    @Transactional
    fun markCheckedIfCursorUnchanged(
        automation: TrackedPlayerAutomation,
        checkedAt: Instant,
    ): Boolean =
        trackedPlayerAutomationJpaRepository.markCheckedIfCursorUnchanged(
            automationId = automation.id,
            expectedLastSeenMatchId = automation.lastSeenMatchId,
            checkedAt = checkedAt,
            updatedAt = checkedAt,
        ) == 1

    @Transactional
    fun advanceCursorIfUnchanged(
        automation: TrackedPlayerAutomation,
        newLastSeenMatchId: String,
        checkedAt: Instant,
    ): Boolean =
        trackedPlayerAutomationJpaRepository.advanceCursorIfUnchanged(
            automationId = automation.id,
            expectedLastSeenMatchId = automation.lastSeenMatchId,
            newLastSeenMatchId = newLastSeenMatchId,
            checkedAt = checkedAt,
            updatedAt = checkedAt,
        ) == 1
}
