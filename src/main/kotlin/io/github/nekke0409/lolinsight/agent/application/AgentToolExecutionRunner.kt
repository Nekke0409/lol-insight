package io.github.nekke0409.lolinsight.agent.application

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val AGENT_TOOL_EXECUTION_EXECUTOR = "agentToolExecutionExecutor"

/**
 * Bounds Agent waiting for an existing Application Service call. Cancelling the Future interrupts
 * this boundary, but it cannot promise cancellation of an HTTP request that has already reached an
 * external client.
 */
interface AgentToolExecutionRunner {
    fun <T> execute(
        timeout: Duration,
        action: () -> T,
    ): T
}

@Component
class BoundedAgentToolExecutionRunner(
    @Qualifier(AGENT_TOOL_EXECUTION_EXECUTOR) private val executor: ExecutorService,
) : AgentToolExecutionRunner {
    override fun <T> execute(
        timeout: Duration,
        action: () -> T,
    ): T {
        val future =
            try {
                executor.submit<T> { action() }
            } catch (exception: RejectedExecutionException) {
                throw AgentToolExecutionUnavailableException(exception)
            }

        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw AgentToolExecutionDeadlineExceededException()
        } catch (exception: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw AgentToolExecutionInterruptedException(exception)
        } catch (exception: ExecutionException) {
            throw (exception.cause as? RuntimeException ?: IllegalStateException("Agent Tool execution failed.", exception.cause))
        }
    }
}

@Configuration(proxyBeanMethods = false)
class AgentToolExecutionConfiguration {
    @Bean(name = [AGENT_TOOL_EXECUTION_EXECUTOR], destroyMethod = "shutdownNow")
    fun agentToolExecutionExecutor(): ExecutorService =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            AgentToolExecutionThreadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )
}

private object AgentToolExecutionThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "agent-tool-execution").apply { isDaemon = true }
}

class AgentToolExecutionDeadlineExceededException : RuntimeException("Agent Tool execution exceeded the request deadline")

class AgentToolExecutionUnavailableException(
    cause: Throwable,
) : RuntimeException(cause)

class AgentToolExecutionInterruptedException(
    cause: Throwable,
) : RuntimeException(cause)
