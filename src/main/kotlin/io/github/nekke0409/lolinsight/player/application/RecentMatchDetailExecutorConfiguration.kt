package io.github.nekke0409.lolinsight.player.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

const val RECENT_MATCH_DETAIL_EXECUTOR = "recentMatchDetailExecutor"
const val MAX_CONCURRENT_MATCH_DETAIL_REQUESTS = 4

@Configuration(proxyBeanMethods = false)
class RecentMatchDetailExecutorConfiguration {
    @Bean(RECENT_MATCH_DETAIL_EXECUTOR)
    fun recentMatchDetailExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = MAX_CONCURRENT_MATCH_DETAIL_REQUESTS
            maxPoolSize = MAX_CONCURRENT_MATCH_DETAIL_REQUESTS
            setThreadNamePrefix("recent-match-detail-")
        }
}
