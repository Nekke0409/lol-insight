package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisAuthenticationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisConfigurationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInvalidResponseException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisTransportException
import io.github.nekke0409.lolinsight.global.riot.RiotApiEmptyResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiInvalidResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.application.MatchNotFoundException
import io.github.nekke0409.lolinsight.player.application.PlayerNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class AnalysisJobFailureCodeMapper {
    fun map(exception: Throwable): AnalysisJobFailureCode =
        when (exception) {
            is PlayerAnalysisConfigurationException -> AnalysisJobFailureCode.CONFIGURATION
            is PlayerAnalysisAuthenticationException -> AnalysisJobFailureCode.AUTHENTICATION
            is PlayerAnalysisRateLimitException -> AnalysisJobFailureCode.RATE_LIMITED
            is PlayerAnalysisProviderException -> AnalysisJobFailureCode.UPSTREAM_UNAVAILABLE
            is PlayerAnalysisTransportException -> AnalysisJobFailureCode.TIMEOUT_NETWORK
            is PlayerAnalysisInvalidResponseException -> AnalysisJobFailureCode.MALFORMED_RESPONSE
            is RiotApiResponseException ->
                if (exception.statusCode == HttpStatus.TOO_MANY_REQUESTS) {
                    AnalysisJobFailureCode.RATE_LIMITED
                } else {
                    AnalysisJobFailureCode.UPSTREAM_UNAVAILABLE
                }
            is RiotApiTransportException -> AnalysisJobFailureCode.TIMEOUT_NETWORK
            is RiotApiEmptyResponseException,
            is RiotApiInvalidResponseException,
            -> AnalysisJobFailureCode.MALFORMED_RESPONSE
            is PlayerNotFoundException,
            is MatchNotFoundException,
            -> AnalysisJobFailureCode.TARGET_NOT_FOUND
            else -> AnalysisJobFailureCode.INTERNAL_ERROR
        }
}
