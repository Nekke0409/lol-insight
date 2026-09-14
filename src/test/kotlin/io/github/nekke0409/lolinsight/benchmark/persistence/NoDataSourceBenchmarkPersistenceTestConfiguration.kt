package io.github.nekke0409.lolinsight.benchmark.persistence

import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@TestConfiguration(proxyBeanMethods = false)
class NoDataSourceBenchmarkPersistenceTestConfiguration {
    @Bean
    fun benchmarkSampleJpaRepository(): BenchmarkSampleJpaRepository = mock(BenchmarkSampleJpaRepository::class.java)

    @Bean
    fun namedParameterJdbcTemplate(): NamedParameterJdbcTemplate = mock(NamedParameterJdbcTemplate::class.java)
}
