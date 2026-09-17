package io.github.nekke0409.lolinsight.analysis.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class AnalysisRateLimitKeyResolver {
    fun resolve(request: HttpServletRequest): AnalysisRateLimitKey = AnalysisRateLimitKey("analysis-generation:${request.remoteAddr}")
}
