package io.github.nekke0409.lolinsight.analysis.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class AnalysisRateLimitKeyResolverTest {
    private val resolver = AnalysisRateLimitKeyResolver()

    @Test
    fun `uses the servlet remote address for the client key`() {
        val request =
            MockHttpServletRequest().apply {
                remoteAddr = "203.0.113.10"
                addHeader("X-Forwarded-For", "198.51.100.5")
            }

        assertEquals(AnalysisRateLimitKey("analysis-generation:203.0.113.10"), resolver.resolve(request))
    }
}
