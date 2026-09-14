package io.github.nekke0409.lolinsight.analysis.application

sealed class PlayerAnalysisException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class PlayerAnalysisConfigurationException : PlayerAnalysisException("AI analysis is not configured")

class PlayerAnalysisAuthenticationException(
    cause: Throwable,
) : PlayerAnalysisException("AI provider authentication or permission failed", cause)

class PlayerAnalysisRateLimitException(
    cause: Throwable,
) : PlayerAnalysisException("AI provider rate limit exceeded", cause)

class PlayerAnalysisProviderException(
    cause: Throwable,
) : PlayerAnalysisException("AI provider request failed", cause)

class PlayerAnalysisTransportException(
    cause: Throwable,
) : PlayerAnalysisException("AI provider transport request failed", cause)

class PlayerAnalysisInvalidResponseException(
    cause: Throwable? = null,
) : PlayerAnalysisException("AI provider returned an invalid analysis response", cause)
