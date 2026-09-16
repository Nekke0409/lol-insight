package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

internal const val AI_GENERATION_REQUESTS_METRIC = "ai.generation.requests"
internal const val AI_GENERATION_DURATION_METRIC = "ai.generation.duration"
internal const val AI_GENERATION_TOKENS_METRIC = "ai.generation.tokens"

internal enum class OpenAiAnalysisFailureCategory(
    val metricValue: String,
) {
    CONFIGURATION("configuration"),
    AUTHENTICATION_PERMISSION("authentication_permission"),
    RATE_LIMIT("rate_limit"),
    UPSTREAM("upstream"),
    TIMEOUT_NETWORK("timeout_network"),
    MALFORMED_STRUCTURED_OUTPUT("malformed_structured_output"),
}

internal data class OpenAiTokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
)

internal interface OpenAiAnalysisObservationRecorder {
    fun recordSuccess(
        model: String,
        latency: Duration,
        usage: OpenAiTokenUsage?,
    )

    fun recordFailure(
        model: String,
        category: OpenAiAnalysisFailureCategory,
        latency: Duration?,
    )
}

internal object NoOpOpenAiAnalysisObservationRecorder : OpenAiAnalysisObservationRecorder {
    override fun recordSuccess(
        model: String,
        latency: Duration,
        usage: OpenAiTokenUsage?,
    ) = Unit

    override fun recordFailure(
        model: String,
        category: OpenAiAnalysisFailureCategory,
        latency: Duration?,
    ) = Unit
}

@Component
internal class OpenAiMicrometerObservationRecorder(
    private val meterRegistry: MeterRegistry,
) : OpenAiAnalysisObservationRecorder {
    override fun recordSuccess(
        model: String,
        latency: Duration,
        usage: OpenAiTokenUsage?,
    ) {
        val tags = tags(model, outcome = "success", errorCategory = "none")
        Counter
            .builder(AI_GENERATION_REQUESTS_METRIC)
            .tags(*tags)
            .register(meterRegistry)
            .increment()
        Timer
            .builder(AI_GENERATION_DURATION_METRIC)
            .tags(*tags)
            .register(meterRegistry)
            .record(latency)
        usage?.let { recordTokenUsage(model, it) }
    }

    override fun recordFailure(
        model: String,
        category: OpenAiAnalysisFailureCategory,
        latency: Duration?,
    ) {
        val tags = tags(model, outcome = "failure", errorCategory = category.metricValue)
        Counter
            .builder(AI_GENERATION_REQUESTS_METRIC)
            .tags(*tags)
            .register(meterRegistry)
            .increment()
        latency?.let {
            Timer
                .builder(AI_GENERATION_DURATION_METRIC)
                .tags(*tags)
                .register(meterRegistry)
                .record(it)
        }
    }

    private fun recordTokenUsage(
        model: String,
        usage: OpenAiTokenUsage,
    ) {
        incrementTokenCounter(model, "input", usage.inputTokens)
        incrementTokenCounter(model, "output", usage.outputTokens)
        incrementTokenCounter(model, "total", usage.totalTokens)
    }

    private fun incrementTokenCounter(
        model: String,
        tokenType: String,
        tokens: Long,
    ) {
        Counter
            .builder(AI_GENERATION_TOKENS_METRIC)
            .tags("provider", "openai", "model", model, "token_type", tokenType)
            .register(meterRegistry)
            .increment(tokens.toDouble())
    }

    private fun tags(
        model: String,
        outcome: String,
        errorCategory: String,
    ): Array<String> =
        arrayOf(
            "provider",
            "openai",
            "model",
            model,
            "outcome",
            outcome,
            "error_category",
            errorCategory,
        )
}
