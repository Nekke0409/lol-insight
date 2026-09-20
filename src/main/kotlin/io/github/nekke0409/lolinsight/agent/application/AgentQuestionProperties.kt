package io.github.nekke0409.lolinsight.agent.application

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("agent")
data class AgentQuestionProperties(
    val enabled: Boolean = false,
    val modelRequestTimeout: Duration = Duration.ofSeconds(30),
    val executionDeadline: Duration = Duration.ofSeconds(60),
    @field:Min(1)
    val maxModelRequests: Int = 3,
    @field:Min(1)
    val maxToolExecutions: Int = 2,
    @field:Min(1)
    val maxQuestionCharacters: Int = 1_000,
    @field:Min(256)
    val maxToolResultCharacters: Int = 12_000,
    @field:Min(1)
    val maxOutputTokens: Long = 600,
) {
    init {
        require(!modelRequestTimeout.isZero && !modelRequestTimeout.isNegative) {
            "agent.model-request-timeout must be positive"
        }
        require(!executionDeadline.isZero && !executionDeadline.isNegative) {
            "agent.execution-deadline must be positive"
        }
    }

    fun requireEnabled() {
        if (!enabled) {
            throw AgentFeatureDisabledException()
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentQuestionProperties::class)
class AgentQuestionConfiguration

class AgentFeatureDisabledException : RuntimeException("Agent feature is disabled")

class AgentQuestionTooLongException : RuntimeException("Agent question exceeds the configured limit")

class AgentModelInvalidResponseException : RuntimeException("Agent model response is invalid")

class AgentModelIncompleteResponseException(
    val incompleteReason: AgentModelIncompleteReason?,
) : RuntimeException("Agent model response is incomplete")

enum class AgentModelIncompleteReason {
    MAX_OUTPUT_TOKENS,
    MAX_MESSAGES,
    CONTENT_FILTER,
    STEERED,
    UNKNOWN,
}

class AgentModelRefusalException : RuntimeException("Agent model refused the request")

class AgentModelConfigurationException : RuntimeException("Agent model configuration is missing")

class AgentModelAuthenticationException(
    cause: Throwable,
) : RuntimeException(cause)

class AgentModelRateLimitException(
    cause: Throwable,
) : RuntimeException(cause)

class AgentModelProviderException(
    cause: Throwable,
) : RuntimeException(cause)

class AgentModelTransportException(
    cause: Throwable,
) : RuntimeException(cause)
