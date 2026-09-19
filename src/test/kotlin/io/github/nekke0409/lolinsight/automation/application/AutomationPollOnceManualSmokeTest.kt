package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID

/**
 * Explicit live polling of one persisted automation. It calls poll(UUID) exactly once, never
 * pollDue(), so neither the scheduler nor other tracking rows are selected by this test.
 */
@EnabledIfEnvironmentVariable(named = "RUN_AUTOMATION_POLL_ONCE_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "RIOT_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTOMATION_SMOKE_AUTOMATION_ID", matches = ".+")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "analysis.automation.enabled=false",
        "analysis.automation.bootstrap.enabled=false",
        "benchmark.replenishment.enabled=false",
        "benchmark.replenishment.run-once=false",
    ],
)
@ActiveProfiles("local")
class AutomationPollOnceManualSmokeTest {
    @Autowired
    private lateinit var pollingService: NewRankedMatchAnalysisPollingService

    @Autowired
    private lateinit var analysisJobQueryService: AnalysisJobQueryService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `polls one requested automation once and waits only for its created job`() {
        val automationId = UUID.fromString(requiredEnvironmentValue("AUTOMATION_SMOKE_AUTOMATION_ID"))
        val outcome = pollingService.poll(automationId)
        println("Automation poll-once smoke: outcome=$outcome, automaticFollowUpPolling=false")

        if (outcome != AutomationPollOutcome.TRIGGERED) {
            return
        }

        val jobId = requireNotNull(findLatestJobId(automationId)) { "Triggered automation execution must reference an AnalysisJob" }
        val terminalJob = awaitTerminalJob(jobId)
        println(
            "Automation poll-once smoke job: status=${terminalJob.status}, " +
                "failureCode=${terminalJob.failureCode}, resultPersisted=${terminalJob.result != null}",
        )
    }

    private fun findLatestJobId(automationId: UUID): UUID? =
        jdbcTemplate
            .query(
                """
                SELECT analysis_job_id
                FROM automation_execution
                WHERE automation_id = ?
                  AND analysis_job_id IS NOT NULL
                ORDER BY detected_at DESC
                LIMIT 1
                """.trimIndent(),
                { resultSet, _ -> resultSet.getObject("analysis_job_id", UUID::class.java) },
                automationId,
            ).firstOrNull()

    private fun awaitTerminalJob(jobId: UUID): AnalysisJobView {
        val deadline = System.nanoTime() + JOB_WAIT_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val job = analysisJobQueryService.find(jobId)
            if (job.status == AnalysisJobStatus.SUCCEEDED || job.status == AnalysisJobStatus.FAILED) {
                return job
            }
            Thread.sleep(JOB_STATUS_POLL_INTERVAL.toMillis())
        }

        println(
            "Automation poll-once smoke job: waitTimeout=${JOB_WAIT_TIMEOUT}, " +
                "waitingStopped=true, jobCancellationRequested=false",
        )
        error("AnalysisJob did not reach a terminal state within $JOB_WAIT_TIMEOUT")
    }

    private fun requiredEnvironmentValue(name: String): String = checkNotNull(System.getenv(name)) { "$name must be set" }

    private companion object {
        val JOB_WAIT_TIMEOUT: Duration = Duration.ofSeconds(90)
        val JOB_STATUS_POLL_INTERVAL: Duration = Duration.ofMillis(250)
    }
}
