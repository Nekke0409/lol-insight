package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobJpaRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals

/**
 * Explicit live registration only. Scheduling and bootstrap remain disabled for this test context,
 * and registration creates a Ranked Solo cursor without creating an AnalysisJob.
 */
@EnabledIfEnvironmentVariable(named = "RUN_AUTOMATION_REGISTRATION_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "RIOT_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_GAME_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TARGET_TAG_LINE", matches = ".+")
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
class AutomationRegistrationManualSmokeTest {
    @Autowired
    private lateinit var registrationService: TrackedPlayerAutomationRegistrationService

    @Autowired
    private lateinit var analysisJobJpaRepository: AnalysisJobJpaRepository

    @Test
    fun `registers one requested player baseline without creating an analysis job`() {
        val jobsBefore = analysisJobJpaRepository.count()
        val automation =
            registrationService.register(
                requiredEnvironmentValue("TARGET_GAME_NAME"),
                requiredEnvironmentValue("TARGET_TAG_LINE"),
            )

        assertEquals(jobsBefore, analysisJobJpaRepository.count())
        println(
            "Automation registration smoke: automationId=${automation.id}, " +
                "baselinePersisted=${automation.lastSeenMatchId != null}, analysisJobCreated=false",
        )
    }

    private fun requiredEnvironmentValue(name: String): String = checkNotNull(System.getenv(name)) { "$name must be set" }
}
