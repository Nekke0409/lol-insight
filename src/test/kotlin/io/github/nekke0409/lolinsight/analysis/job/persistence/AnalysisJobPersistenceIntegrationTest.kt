package io.github.nekke0409.lolinsight.analysis.job.persistence

import io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobFailureCode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobLifecycleService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobResultCodec
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    AnalysisJobLifecycleService::class,
    AnalysisJobQueryService::class,
    AnalysisJobResultCodec::class,
    AnalysisJobPersistenceIntegrationTest.PersistenceTestConfiguration::class,
)
@Testcontainers
class AnalysisJobPersistenceIntegrationTest {
    @Autowired
    private lateinit var lifecycleService: AnalysisJobLifecycleService

    @Autowired
    private lateinit var queryService: AnalysisJobQueryService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `Flyway migration persists and restores a successful JSONB analysis snapshot`() {
        val created = lifecycleService.createPending()
        val result =
            PlayerAnalysisResult(
                summary = "summary",
                observations = listOf(AnalysisInsight("title", "explanation", "evidence")),
                strengths = emptyList(),
                focusAreas = emptyList(),
                caveats = listOf("caveat"),
            )

        assertEquals(AnalysisJobStatus.PENDING, queryService.find(created.jobId).status)
        assertEquals(created, lifecycleService.findInFlight(created.jobId))
        assertTrue(lifecycleService.markRunningIfPending(created.jobId))
        assertEquals(AnalysisJobStatus.RUNNING, lifecycleService.findInFlight(created.jobId)?.status)
        assertTrue(lifecycleService.markSucceededIfRunning(created.jobId, result))
        assertNull(lifecycleService.findInFlight(created.jobId))

        val restored = queryService.find(created.jobId)
        assertEquals(AnalysisJobStatus.SUCCEEDED, restored.status)
        assertEquals(result, restored.result)
        assertNull(restored.failureCode)
        assertEquals(
            "object",
            jdbcTemplate.queryForObject(
                "select jsonb_typeof(result) from analysis_job where id = ?",
                String::class.java,
                created.jobId,
            ),
        )
        assertFalse(lifecycleService.markRunningIfPending(created.jobId))
        assertFalse(lifecycleService.markFailedIfRunning(created.jobId, AnalysisJobFailureCode.INTERNAL_ERROR))
    }

    @Test
    fun `failed lifecycle persists only the safe failure code`() {
        val created = lifecycleService.createPending()

        assertTrue(lifecycleService.markRunningIfPending(created.jobId))
        assertTrue(lifecycleService.markFailedIfRunning(created.jobId, AnalysisJobFailureCode.RATE_LIMITED))
        assertNull(lifecycleService.findInFlight(created.jobId))

        val restored = queryService.find(created.jobId)
        assertEquals(AnalysisJobStatus.FAILED, restored.status)
        assertEquals(AnalysisJobFailureCode.RATE_LIMITED, restored.failureCode)
        assertNull(restored.result)
        assertFalse(
            lifecycleService.markSucceededIfRunning(
                created.jobId,
                PlayerAnalysisResult("later", emptyList(), emptyList(), emptyList(), emptyList()),
            ),
        )
    }

    @Test
    fun `rejected pending job is retained as a capacity audit row`() {
        val created = lifecycleService.createPending()

        assertTrue(lifecycleService.markRejectedIfPending(created.jobId))

        val restored = queryService.find(created.jobId)
        assertEquals(AnalysisJobStatus.FAILED, restored.status)
        assertEquals(AnalysisJobFailureCode.CAPACITY_EXCEEDED, restored.failureCode)
        assertNull(restored.startedAt)
        assertFalse(lifecycleService.markRunningIfPending(created.jobId))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PersistenceTestConfiguration {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC)
    }

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
