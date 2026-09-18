package io.github.nekke0409.lolinsight.benchmark.application

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class BenchmarkQueryWindowFactoryTest {
    @Test
    fun `creates a UTC rolling duration window from the injected clock`() {
        val asOf = Instant.parse("2026-09-14T00:00:00Z")
        val window =
            BenchmarkQueryWindowFactory(
                BenchmarkSampleProperties(maxAge = Duration.ofDays(30)),
                Clock.fixed(asOf, ZoneOffset.UTC),
            ).current()

        assertEquals(Instant.parse("2026-08-15T00:00:00Z"), window.fromInclusive)
        assertEquals(asOf, window.toExclusive)
    }
}
