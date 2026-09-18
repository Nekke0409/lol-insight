package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentCohort
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentCursorService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BenchmarkReplenishmentCursorService::class, BenchmarkReplenishmentCursorPersistenceIntegrationTest.FixedClockConfiguration::class)
@Testcontainers
class BenchmarkReplenishmentCursorPersistenceIntegrationTest {
    @Autowired
    private lateinit var cursorService: BenchmarkReplenishmentCursorService

    @Autowired
    private lateinit var repository: BenchmarkReplenishmentCursorJpaRepository

    @Test
    fun `Flyway persists one cursor per cohort and reloads its next page`() {
        val cohort = BenchmarkReplenishmentCohort("GOLD", "I")
        val initial = cursorService.getOrCreate(cohort)

        cursorService.advance(initial, 6)

        val reloaded = cursorService.getOrCreate(cohort)
        assertEquals(6, reloaded.nextPage)
        assertEquals(1, repository.count())
        assertEquals(Instant.parse("2026-09-19T00:00:00Z"), reloaded.lastAttemptedAt)
    }

    @Test
    fun `uses separate cursors for separate rank cohorts`() {
        assertEquals(1, cursorService.getOrCreate(BenchmarkReplenishmentCohort("GOLD", "I")).nextPage)
        assertEquals(1, cursorService.getOrCreate(BenchmarkReplenishmentCohort("PLATINUM", "I")).nextPage)

        assertEquals(2, repository.count())
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

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC)
    }
}
