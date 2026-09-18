package io.github.nekke0409.lolinsight.automation.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class AutomationObservationRecorder(
    private val meterRegistry: MeterRegistry,
) {
    fun recordPollSuccess() = increment("analysis.automation.polls", "outcome", "success")

    fun recordPollFailure() = increment("analysis.automation.polls", "outcome", "failure")

    fun recordPollRateLimited() = increment("analysis.automation.polls", "outcome", "rate_limited")

    fun recordPollCooldownSkipped() = increment("analysis.automation.polls", "outcome", "cooldown_skipped")

    fun recordNewMatchDetected() = increment("analysis.automation.matches.detected")

    fun recordTriggerTriggered() = increment("analysis.automation.triggers", "outcome", "triggered")

    fun recordTriggerSkipped() = increment("analysis.automation.triggers", "outcome", "skipped_idempotent")

    fun recordTriggerFailure() = increment("analysis.automation.triggers", "outcome", "failure")

    private fun increment(
        name: String,
        vararg tags: String,
    ) {
        runCatching { meterRegistry.counter(name, *tags).increment() }
    }
}
