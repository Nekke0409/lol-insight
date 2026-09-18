package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class BenchmarkQueryWindowFactory(
    private val properties: BenchmarkSampleProperties,
    private val clock: Clock,
) {
    fun current(): BenchmarkQueryWindow {
        val asOf = clock.instant()
        return BenchmarkQueryWindow(
            fromInclusive = asOf.minus(properties.maxAge),
            toExclusive = asOf,
        )
    }
}
