package io.github.nekke0409.lolinsight.match.application

import com.sun.net.httpserver.HttpServer
import io.github.nekke0409.lolinsight.global.riot.MicrometerRiotApiObservationRecorder
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiOutboundPacer
import io.github.nekke0409.lolinsight.global.riot.RiotApiOutboundPacingProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiPacingTimeSource
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.SystemRiotApiPacingTimeSource
import io.github.nekke0409.lolinsight.match.infrastructure.cache.MATCH_DETAIL_CACHE
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchDetailBatchLoaderPacingTest {
    @Test
    fun `twenty cache misses pass through four workers and one shared pacer in input order`() {
        MatchServer().use { server ->
            val registry = SimpleMeterRegistry()
            val time = VirtualPacingTimeSource()
            val client = client(server.uri, Duration.ofMillis(5), Duration.ofSeconds(1), time, registry)
            val executor = MatchDetailExecutorConfiguration().matchDetailExecutor().apply { initialize() }
            try {
                val ids = (1..20).map { "M$it" }
                val result = MatchDetailBatchLoader(client, executor).load(ids)

                assertEquals(ids, result.map { it.matchId })
                assertTrue(result.all { it is MatchDetailLoadSuccess })
                assertEquals(20, server.requestCount.get())
                assertTrue(server.maxConcurrent.get() <= MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
                assertEquals(
                    20.0,
                    registry
                        .get("riot.api.http.attempts")
                        .tag("endpoint", "match_detail")
                        .counter()
                        .count(),
                )
                assertEquals(
                    20.0,
                    registry
                        .get("riot.api.http.responses")
                        .tag("endpoint", "match_detail")
                        .tag("status", "200")
                        .counter()
                        .count(),
                )
                assertEquals(19, time.waits.get())
                assertEquals(TimeUnit.MILLISECONDS.toNanos(95), time.elapsed.get())
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun `overload is rejected locally without retrying the failed match`() {
        MatchServer().use { server ->
            val registry = SimpleMeterRegistry()
            val client =
                client(
                    server.uri,
                    Duration.ofMillis(200),
                    Duration.ofMillis(20),
                    SystemRiotApiPacingTimeSource(),
                    registry,
                )
            val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
            try {
                val result = MatchDetailBatchLoader(client, executor).load((1..20).map { "M$it" })

                assertTrue(result.any { it is MatchDetailLoadFailure })
                assertTrue(
                    registry
                        .get("riot.api.pacing.admissions")
                        .tag("outcome", "timeout")
                        .counter()
                        .count() >= 1.0,
                )
                assertTrue(server.requestCount.get() < 20)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `upstream 429 stops the batch and pending workers do not start late HTTP`() {
        MatchServer(status = 429).use { server ->
            val registry = SimpleMeterRegistry()
            val client =
                client(
                    server.uri,
                    Duration.ofMillis(300),
                    Duration.ofSeconds(2),
                    SystemRiotApiPacingTimeSource(),
                    registry,
                )
            val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
            try {
                val result = MatchDetailBatchLoader(client, executor).load((1..20).map { "M$it" })
                executor.shutdown()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))

                assertTrue(result.any { it is MatchDetailLoadFailure && it.exception is RiotApiResponseException })
                assertEquals(1, server.requestCount.get())
                assertEquals(1.0, registry.get("riot.api.http.rate_limits").counter().count())
                assertEquals(
                    1.0,
                    registry
                        .get("riot.api.http.responses")
                        .tag("endpoint", "match_detail")
                        .tag("status", "429")
                        .counter()
                        .count(),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `match cache hit does not consume another HTTP attempt or pacing admission`() {
        MatchServer().use { server ->
            val registry = SimpleMeterRegistry()
            val target = client(server.uri, Duration.ofMillis(5), Duration.ofSeconds(1), VirtualPacingTimeSource(), registry)
            val context = AnnotationConfigApplicationContext()
            context.register(CacheTestConfiguration::class.java)
            context.registerBean("riotMatchClient", RiotMatchClient::class.java, Supplier { target })
            context.refresh()
            try {
                val cachedClient = context.getBean(RiotMatchClient::class.java)
                assertEquals("M1", cachedClient.findMatchById("M1").matchId)
                assertEquals("M1", cachedClient.findMatchById("M1").matchId)
            } finally {
                context.close()
            }
            assertEquals(1, server.requestCount.get())
            assertEquals(
                1.0,
                registry
                    .get("riot.api.http.attempts")
                    .tag("endpoint", "match_detail")
                    .counter()
                    .count(),
            )
            assertEquals(1L, registry.get("riot.api.pacing.admission_elapsed").timer().count())
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    private class CacheTestConfiguration {
        @Bean
        fun cacheManager(): CacheManager = ConcurrentMapCacheManager(MATCH_DETAIL_CACHE)
    }

    private fun client(
        baseUrl: URI,
        interval: Duration,
        maxWait: Duration,
        timeSource: RiotApiPacingTimeSource,
        registry: SimpleMeterRegistry,
    ): RiotMatchClient {
        val properties =
            RiotApiProperties(
                key = "test-key",
                regionalBaseUrl = baseUrl,
                outboundPacing = RiotApiOutboundPacingProperties(true, interval, maxWait),
            )
        val cooldown = RiotApiCooldown(properties, Clock.systemUTC())
        val recorder = MicrometerRiotApiObservationRecorder(registry)
        return RiotMatchClient(
            RiotApiHttpClient(
                RestClient.builder().build(),
                properties,
                cooldown,
                RiotApiOutboundPacer(properties, cooldown, timeSource, recorder),
                recorder,
            ),
        )
    }

    private class VirtualPacingTimeSource : RiotApiPacingTimeSource {
        private val now = AtomicLong(1L)
        val elapsed = AtomicLong()
        val waits = AtomicInteger()

        override fun nanoTime(): Long = now.get()

        override fun await(duration: Duration) {
            waits.incrementAndGet()
            elapsed.addAndGet(duration.toNanos())
            now.addAndGet(duration.toNanos())
        }
    }

    private class MatchServer(
        private val status: Int = 200,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val fixture = requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail.json")).readText()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestCount = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()
        val uri: URI
            get() = URI.create("http://127.0.0.1:${server.address.port}")

        init {
            server.executor = executor
            server.createContext("/lol/match/v5/matches/") { exchange ->
                requestCount.incrementAndGet()
                maxConcurrent.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    val body =
                        if (status == 200) {
                            fixture.replace("KR_1234567890", exchange.requestURI.path.substringAfterLast('/'))
                        } else {
                            ""
                        }.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (status == 429) exchange.responseHeaders.add("Retry-After", "5")
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } finally {
                    active.decrementAndGet()
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
