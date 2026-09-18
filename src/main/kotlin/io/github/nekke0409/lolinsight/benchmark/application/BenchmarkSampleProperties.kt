package io.github.nekke0409.lolinsight.benchmark.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("benchmark.sample")
data class BenchmarkSampleProperties(
    val maxAge: Duration = Duration.ofDays(30),
) {
    init {
        require(!maxAge.isNegative && !maxAge.isZero) { "benchmark sample maxAge must be positive" }
    }
}
