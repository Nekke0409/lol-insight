package io.github.nekke0409.lolinsight.automation.scheduling

import io.github.nekke0409.lolinsight.automation.application.NewRankedMatchAnalysisPollingService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.scheduling.annotation.Scheduled
import kotlin.test.assertEquals

class NewRankedMatchAnalysisSchedulerTest {
    @Test
    fun `scheduled adapter delegates the tick to the polling application service`() {
        val pollingService = mock(NewRankedMatchAnalysisPollingService::class.java)

        NewRankedMatchAnalysisScheduler(pollingService).pollDueAutomations()

        verify(pollingService).pollDue()
    }

    @Test
    fun `scheduled adapter reads the same polling property used by due selection`() {
        val scheduled =
            NewRankedMatchAnalysisScheduler::class.java
                .getDeclaredMethod("pollDueAutomations")
                .getAnnotation(Scheduled::class.java)

        assertEquals("\${analysis.automation.poll-interval}", scheduled.fixedDelayString)
    }
}
