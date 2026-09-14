package io.github.nekke0409.lolinsight.match.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

const val MATCH_DETAIL_EXECUTOR = "recentMatchDetailExecutor"
const val MAX_CONCURRENT_MATCH_DETAIL_REQUESTS = 4

@Configuration(proxyBeanMethods = false)
class MatchDetailExecutorConfiguration {
    @Bean(MATCH_DETAIL_EXECUTOR)
    fun matchDetailExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = MAX_CONCURRENT_MATCH_DETAIL_REQUESTS
            maxPoolSize = MAX_CONCURRENT_MATCH_DETAIL_REQUESTS
            setThreadNamePrefix("match-detail-")
        }
}
