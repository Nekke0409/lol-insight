package io.github.nekke0409.lolinsight.automation.scheduling

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("analysis.automation")
data class AnalysisAutomationProperties(
    val enabled: Boolean = false,
    val pollInterval: Duration = Duration.ofMinutes(30),
    @field:Min(1)
    @field:Max(20)
    val batchSize: Int = 10,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero) { "pollInterval must be positive." }
    }
}

@ConfigurationProperties("analysis.automation.bootstrap")
data class AnalysisAutomationBootstrapProperties(
    val enabled: Boolean = false,
    val players: List<String> = emptyList(),
)
