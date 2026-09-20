package io.github.nekke0409.lolinsight.agent.application

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class BoundedAgentToolExecutionRunnerTest {
    @Test
    fun `stops waiting at the deadline and interrupts the Tool boundary`() {
        val executor =
            ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                SynchronousQueue(),
                ThreadPoolExecutor.AbortPolicy(),
            )
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val runner = BoundedAgentToolExecutionRunner(executor)
        val caller = Executors.newSingleThreadExecutor()

        try {
            val result =
                caller.submit<Throwable?> {
                    runCatching {
                        runner.execute(Duration.ofMillis(200)) {
                            started.countDown()
                            try {
                                CountDownLatch(1).await()
                            } catch (_: InterruptedException) {
                                interrupted.countDown()
                                throw IllegalStateException("interrupted by the deadline boundary")
                            }
                        }
                    }.exceptionOrNull()
                }

            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(result.get(1, TimeUnit.SECONDS) is AgentToolExecutionDeadlineExceededException)
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        } finally {
            caller.shutdownNow()
            executor.shutdownNow()
        }
    }
}
