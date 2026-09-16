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
    val reasoningEffort: String = "",
    val textVerbosity: String = "",
) {
    fun requireConfigured() {
        if (apiKey.isBlank() || model.isBlank()) {
            throw OpenAiConfigurationException()
        }
    }

    fun requestedReasoningEffort(): ReasoningEffort? =
        when (reasoningEffort.trim()) {
            "" -> null
            "low" -> ReasoningEffort.LOW
            else -> throw OpenAiConfigurationException()
        }

    fun requestedTextVerbosity(): ResponseTextConfig.Verbosity? =
        when (textVerbosity.trim()) {
            "" -> null
            "low" -> ResponseTextConfig.Verbosity.LOW
            else -> throw OpenAiConfigurationException()
        }
}

class OpenAiConfigurationException : RuntimeException("OpenAI configuration is missing")
