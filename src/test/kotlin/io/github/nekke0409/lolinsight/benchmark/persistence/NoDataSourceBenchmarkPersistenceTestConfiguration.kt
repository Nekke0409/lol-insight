package io.github.nekke0409.lolinsight.benchmark.persistence

import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration(proxyBeanMethods = false)
class NoDataSourceBenchmarkPersistenceTestConfiguration {
    @Bean
    fun benchmarkSampleJpaRepository(): BenchmarkSampleJpaRepository = mock(BenchmarkSampleJpaRepository::class.java)
}
