package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.models.ReasoningEffort
import com.openai.models.responses.ResponseTextConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val model: String = "",
    val timeout: Duration = Duration.ofSeconds(60),
    val reasoningEffort: String = "low",
    val textVerbosity: String = "low",
) {
    fun requireConfigured() {
        if (apiKey.isBlank() || model.isBlank()) {
            throw OpenAiConfigurationException()
        }
    }

    fun requestedReasoningEffort(): ReasoningEffort =
        when (reasoningEffort.trim()) {
            "minimal" -> ReasoningEffort.MINIMAL
            "low" -> ReasoningEffort.LOW
            "medium" -> ReasoningEffort.MEDIUM
            "high" -> ReasoningEffort.HIGH
            else -> throw OpenAiConfigurationException()
        }

    fun requestedTextVerbosity(): ResponseTextConfig.Verbosity =
        when (textVerbosity.trim()) {
            "low" -> ResponseTextConfig.Verbosity.LOW
            "medium" -> ResponseTextConfig.Verbosity.MEDIUM
            "high" -> ResponseTextConfig.Verbosity.HIGH
            else -> throw OpenAiConfigurationException()
        }
}

class OpenAiConfigurationException : RuntimeException("OpenAI configuration is missing")
