package io.github.nekke0409.lolinsight.analysis.job.web

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobFailureCode
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobNotFoundException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobView
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class AnalysisJobControllerTest {
    private val queryService = mock(AnalysisJobQueryService::class.java)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AnalysisJobController(queryService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `returns pending and running jobs without a result`() {
        `when`(queryService.find(PENDING_ID)).thenReturn(pending())
        `when`(queryService.find(RUNNING_ID)).thenReturn(running())

        mockMvc
            .perform(get("/api/v1/analysis-jobs/{jobId}", PENDING_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.result").doesNotExist())
            .andExpect(jsonPath("$.failureCode").doesNotExist())

        mockMvc
            .perform(get("/api/v1/analysis-jobs/{jobId}", RUNNING_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.startedAt").value("2026-09-16T10:01:00Z"))
            .andExpect(jsonPath("$.result").doesNotExist())
    }

    @Test
    fun `returns persisted result or safe failure code for terminal jobs`() {
        `when`(queryService.find(SUCCEEDED_ID)).thenReturn(succeeded())
        `when`(queryService.find(FAILED_ID)).thenReturn(failed())

        mockMvc
            .perform(get("/api/v1/analysis-jobs/{jobId}", SUCCEEDED_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.result.summary").value("summary"))
            .andExpect(jsonPath("$.failureCode").doesNotExist())

        mockMvc
            .perform(get("/api/v1/analysis-jobs/{jobId}", FAILED_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.result").doesNotExist())
    }

    @Test
    fun `returns not found for an unknown job`() {
        `when`(queryService.find(PENDING_ID)).thenThrow(AnalysisJobNotFoundException(PENDING_ID))

        mockMvc
            .perform(get("/api/v1/analysis-jobs/{jobId}", PENDING_ID))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("Analysis job not found."))
    }

    private fun pending(): AnalysisJobView = AnalysisJobView(PENDING_ID, AnalysisJobStatus.PENDING, null, null, CREATED_AT, null, null)

    private fun running(): AnalysisJobView =
        AnalysisJobView(RUNNING_ID, AnalysisJobStatus.RUNNING, null, null, CREATED_AT, STARTED_AT, null)

    private fun succeeded(): AnalysisJobView =
        AnalysisJobView(
            SUCCEEDED_ID,
            AnalysisJobStatus.SUCCEEDED,
            PlayerAnalysisResult("summary", emptyList(), emptyList(), emptyList(), emptyList()),
            null,
            CREATED_AT,
            STARTED_AT,
            COMPLETED_AT,
        )

    private fun failed(): AnalysisJobView =
        AnalysisJobView(
            FAILED_ID,
            AnalysisJobStatus.FAILED,
            null,
            AnalysisJobFailureCode.RATE_LIMITED,
            CREATED_AT,
            STARTED_AT,
            COMPLETED_AT,
        )

    private companion object {
        val PENDING_ID = UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf")
        val RUNNING_ID = UUID.fromString("1c565b91-2ac1-4e7a-b2d1-7adc3c766938")
        val SUCCEEDED_ID = UUID.fromString("1fe84b4f-d3cf-4451-8aae-bc8f87cf60c8")
        val FAILED_ID = UUID.fromString("e4368ca6-bc58-43f5-8352-98ef4fd1e834")
        val CREATED_AT = Instant.parse("2026-09-16T10:00:00Z")
        val STARTED_AT = Instant.parse("2026-09-16T10:01:00Z")
        val COMPLETED_AT = Instant.parse("2026-09-16T10:02:00Z")
    }
}
