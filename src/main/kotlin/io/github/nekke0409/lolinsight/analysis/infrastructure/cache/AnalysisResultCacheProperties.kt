package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

const val ANALYSIS_RESULT_CACHE_VERSION = "analysis-result-v1"

@ConfigurationProperties("analysis.result-cache")
data class AnalysisResultCacheProperties(
    val ttl: Duration = Duration.ofMinutes(30),
    val version: String = ANALYSIS_RESULT_CACHE_VERSION,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "analysis result cache ttl must be positive" }
        require(version.matches(Regex("[a-z0-9-]+"))) {
            "analysis result cache version must contain only lowercase letters, numbers, and hyphens"
        }
    }
}
