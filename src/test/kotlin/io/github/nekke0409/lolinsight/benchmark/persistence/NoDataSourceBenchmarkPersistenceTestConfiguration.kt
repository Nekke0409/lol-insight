package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.analysis.job.persistence.AnalysisJobJpaRepository
import io.github.nekke0409.lolinsight.automation.persistence.AutomationExecutionJpaRepository
import io.github.nekke0409.lolinsight.automation.persistence.TrackedPlayerAutomationJpaRepository
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@TestConfiguration(proxyBeanMethods = false)
class NoDataSourceBenchmarkPersistenceTestConfiguration {
    @Bean
    fun analysisJobJpaRepository(): AnalysisJobJpaRepository = mock(AnalysisJobJpaRepository::class.java)

    @Bean
    fun automationExecutionJpaRepository(): AutomationExecutionJpaRepository = mock(AutomationExecutionJpaRepository::class.java)

    @Bean
    fun trackedPlayerAutomationJpaRepository(): TrackedPlayerAutomationJpaRepository =
        mock(TrackedPlayerAutomationJpaRepository::class.java)

    @Bean
    fun benchmarkSampleJpaRepository(): BenchmarkSampleJpaRepository = mock(BenchmarkSampleJpaRepository::class.java)

    @Bean
    fun benchmarkReplenishmentCursorJpaRepository(): BenchmarkReplenishmentCursorJpaRepository =
        mock(BenchmarkReplenishmentCursorJpaRepository::class.java)

    @Bean
    fun namedParameterJdbcTemplate(): NamedParameterJdbcTemplate = mock(NamedParameterJdbcTemplate::class.java)
}
