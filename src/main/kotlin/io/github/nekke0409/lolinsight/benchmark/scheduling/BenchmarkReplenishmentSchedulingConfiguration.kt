package io.github.nekke0409.lolinsight.benchmark.scheduling

import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentProperties
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentTickService
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BenchmarkReplenishmentProperties::class)
class BenchmarkReplenishmentConfiguration

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "benchmark.replenishment", name = ["enabled"], havingValue = "true")
class BenchmarkReplenishmentSchedulingConfiguration

@Component
@ConditionalOnProperty(prefix = "benchmark.replenishment", name = ["enabled"], havingValue = "true")
class BenchmarkReplenishmentScheduler(
    private val benchmarkReplenishmentTickService: BenchmarkReplenishmentTickService,
) {
    @Scheduled(fixedDelayString = "\${benchmark.replenishment.interval}")
    fun replenish() {
        benchmarkReplenishmentTickService.runOneTick()
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "benchmark.replenishment", name = ["run-once"], havingValue = "true")
class BenchmarkReplenishmentRunOnceConfiguration {
    @Bean
    fun benchmarkReplenishmentRunOnceDiagnosticLogger(): BenchmarkReplenishmentRunOnceDiagnosticLogger =
        BenchmarkReplenishmentRunOnceDiagnosticLogger()

    @Bean
    fun benchmarkReplenishmentRunOnceRunner(
        benchmarkReplenishmentTickService: BenchmarkReplenishmentTickService,
        diagnosticLogger: BenchmarkReplenishmentRunOnceDiagnosticLogger,
    ): ApplicationRunner =
        ApplicationRunner {
            diagnosticLogger.log(benchmarkReplenishmentTickService.runOneTick())
        }
}
