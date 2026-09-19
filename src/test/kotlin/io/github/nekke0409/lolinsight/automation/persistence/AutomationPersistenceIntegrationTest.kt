package io.github.nekke0409.lolinsight.automation.persistence

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobEntity
import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobJpaRepository
import io.github.nekke0409.lolinsight.automation.application.AutomationExecutionClaim
import io.github.nekke0409.lolinsight.automation.application.AutomationExecutionService
import io.github.nekke0409.lolinsight.automation.application.TrackedPlayerAutomationService
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.scheduling.AnalysisAutomationProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TrackedPlayerAutomationService::class, AutomationExecutionService::class)
@Testcontainers
class AutomationPersistenceIntegrationTest {
    @Autowired
    private lateinit var trackedPlayerAutomationService: TrackedPlayerAutomationService

    @Autowired
    private lateinit var automationExecutionService: AutomationExecutionService

    @Autowired
    private lateinit var trackedPlayerAutomationJpaRepository: TrackedPlayerAutomationJpaRepository

    @Autowired
    private lateinit var automationExecutionJpaRepository: AutomationExecutionJpaRepository

    @Autowired
    private lateinit var analysisJobJpaRepository: AnalysisJobJpaRepository

    @Test
    fun `Flyway mapping persists cursor and one idempotent execution per detected match`() {
        val registeredAt = Instant.parse("2026-09-18T12:00:00Z")
        val automation = trackedPlayerAutomationService.createIfAbsent("stable-puuid", "KR_100", registeredAt)
        val jobId = UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf")
        analysisJobJpaRepository.saveAndFlush(
            AnalysisJobEntity(
                id = jobId,
                status = AnalysisJobStatus.PENDING,
                createdAt = registeredAt,
            ),
        )

        val firstClaim = automationExecutionService.claim(automation.id, "KR_101", registeredAt)
        val execution = assertIs<AutomationExecutionClaim.Created>(firstClaim).execution
        val duplicateClaim = automationExecutionService.claim(automation.id, "KR_101", registeredAt.plusSeconds(1))

        assertIs<AutomationExecutionClaim.Existing>(duplicateClaim)
        assertEquals(1, automationExecutionJpaRepository.count())
        assertTrue(automationExecutionService.markJobCreated(execution.id, jobId, registeredAt.plusSeconds(2)))
        assertTrue(trackedPlayerAutomationService.advanceCursorIfUnchanged(automation, "KR_101", registeredAt.plusSeconds(2)))
        assertTrue(automationExecutionService.markTriggered(execution.id, registeredAt.plusSeconds(2)))

        val restoredAutomation = requireNotNull(trackedPlayerAutomationJpaRepository.findById(automation.id).orElse(null))
        val restoredExecution = requireNotNull(automationExecutionJpaRepository.findById(execution.id).orElse(null))
        assertEquals("KR_101", restoredAutomation.lastSeenMatchId)
        assertEquals(jobId, restoredExecution.analysisJobId)
        assertEquals(AutomationExecutionStatus.TRIGGERED, restoredExecution.status)
        assertFalse(trackedPlayerAutomationService.advanceCursorIfUnchanged(automation, "KR_102", registeredAt.plusSeconds(3)))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent claims for the same detected match produce exactly one execution`() {
        val detectedAt = Instant.parse("2026-09-18T12:00:00Z")
        val automation = trackedPlayerAutomationService.createIfAbsent("concurrent-puuid", "KR_100", detectedAt)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val claims =
                executor
                    .invokeAll(
                        List(2) {
                            java.util.concurrent.Callable {
                                barrier.await()
                                automationExecutionService.claim(automation.id, "KR_101", detectedAt)
                            }
                        },
                    ).map { it.get() }

            assertEquals(1, claims.count { it is AutomationExecutionClaim.Created })
            assertEquals(1, claims.count { it is AutomationExecutionClaim.Existing })
            assertEquals(1, automationExecutionJpaRepository.count())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `due query excludes a check newer than thirty minutes and includes the exact boundary`() {
        val now = Instant.parse("2026-09-20T12:00:00Z")
        val pollInterval = AnalysisAutomationProperties().pollInterval
        val dueBefore = now.minus(pollInterval)
        val justChecked = dueBefore.plusSeconds(1)
        val olderChecked = dueBefore.minusSeconds(1)
        val exactBoundary = automationEntity("exact-boundary", dueBefore)
        val older = automationEntity("older", olderChecked)

        trackedPlayerAutomationJpaRepository.saveAllAndFlush(
            listOf(
                automationEntity("too-recent", justChecked),
                exactBoundary,
                older,
            ),
        )

        val due = trackedPlayerAutomationService.findDue(dueBefore, 10)

        assertEquals(Duration.ofMinutes(30), pollInterval)
        assertEquals(setOf(exactBoundary.id, older.id), due.map { it.id }.toSet())
    }

    private fun automationEntity(
        puuid: String,
        lastCheckedAt: Instant,
    ): TrackedPlayerAutomationEntity =
        TrackedPlayerAutomationEntity(
            puuid = puuid,
            lastSeenMatchId = "KR_100",
            lastCheckedAt = lastCheckedAt,
            createdAt = lastCheckedAt,
            updatedAt = lastCheckedAt,
        )

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
