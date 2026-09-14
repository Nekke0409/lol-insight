package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val model: String = "",
    val timeout: Duration = Duration.ofSeconds(20),
) {
    fun requireConfigured() {
        if (apiKey.isBlank() || model.isBlank()) {
            throw OpenAiConfigurationException()
        }
    }
}

class OpenAiConfigurationException : RuntimeException("OpenAI configuration is missing")
