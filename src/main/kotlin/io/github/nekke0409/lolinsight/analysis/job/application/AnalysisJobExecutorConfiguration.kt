package io.github.nekke0409.lolinsight.analysis.job.application

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.validation.annotation.Validated
import java.util.concurrent.ThreadPoolExecutor

const val PLAYER_ANALYSIS_JOB_EXECUTOR = "playerAnalysisJobExecutor"

@Validated
@ConfigurationProperties("analysis.jobs.executor")
data class AnalysisJobExecutorProperties(
    @field:Min(1)
    val workerThreads: Int = 1,
    @field:Min(1)
    val queueCapacity: Int = 2,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisJobExecutorProperties::class)
class AnalysisJobExecutorConfiguration {
    @Bean(PLAYER_ANALYSIS_JOB_EXECUTOR)
    fun playerAnalysisJobExecutor(properties: AnalysisJobExecutorProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.workerThreads
            maxPoolSize = properties.workerThreads
            setQueueCapacity(properties.queueCapacity)
            setThreadNamePrefix("analysis-job-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        }
}
