package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisGenerator
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInputMapper
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResultCache
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobDispatcher
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobFailureCodeMapper
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobInFlightRegistry
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobLifecycleService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobQueryService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobResultCodec
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobView
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobWorker
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.observability.AutomationObservationRecorder
import io.github.nekke0409.lolinsight.automation.persistence.AutomationExecutionJpaRepository
import io.github.nekke0409.lolinsight.automation.scheduling.AnalysisAutomationProperties
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityConfiguration
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkAvailabilityPolicy
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkQueryWindowFactory
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSampleProperties
import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleJpaRepository
import io.github.nekke0409.lolinsight.benchmark.persistence.toEntity
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonAvailabilityPolicy
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContext
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextPlayer
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextSample
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerPositionStatistics
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    TrackedPlayerAutomationService::class,
    AutomationExecutionService::class,
    AnalysisJobLifecycleService::class,
    AnalysisJobQueryService::class,
    AnalysisJobResultCodec::class,
    BenchmarkAvailabilityConfiguration::class,
    BenchmarkAvailabilityPolicy::class,
    BenchmarkQueryWindowFactory::class,
    BenchmarkSampleAggregateRepository::class,
    PeerBenchmarkQueryService::class,
    PlayerComparisonAvailabilityPolicy::class,
    PlayerComparisonFeatureService::class,
    NewRankedMatchAnalysisWorkflowIntegrationTest.ContextServiceStubConfiguration::class,
    NewRankedMatchAnalysisWorkflowIntegrationTest.FixedClockConfiguration::class,
)
@Testcontainers
class NewRankedMatchAnalysisWorkflowIntegrationTest {
    @jakarta.annotation.Resource
    private lateinit var trackedPlayerAutomationService: TrackedPlayerAutomationService

    @jakarta.annotation.Resource
    private lateinit var automationExecutionService: AutomationExecutionService

    @jakarta.annotation.Resource
    private lateinit var automationExecutionJpaRepository: AutomationExecutionJpaRepository

    @jakarta.annotation.Resource
    private lateinit var lifecycleService: AnalysisJobLifecycleService

    @jakarta.annotation.Resource
    private lateinit var queryService: AnalysisJobQueryService

    @jakarta.annotation.Resource
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @jakarta.annotation.Resource
    private lateinit var playerComparisonContextService: PlayerComparisonContextService

    @jakarta.annotation.Resource
    private lateinit var playerComparisonFeatureService: PlayerComparisonFeatureService

    @jakarta.annotation.Resource
    private lateinit var clock: Clock

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `registration detection and a repeated poll produce one completed job through the real comparison feature`() {
        val riotMatchClient = mock(RiotMatchClient::class.java)
        val playerService = mock(PlayerService::class.java)
        val resultCache = InMemoryResultCache()
        val generator = BlockingGenerator()
        val registrationService =
            TrackedPlayerAutomationRegistrationService(
                playerService,
                riotMatchClient,
                trackedPlayerAutomationService,
                clock,
            )
        val playerAnalysisService =
            PlayerAnalysisService(
                playerComparisonFeatureService,
                PlayerAnalysisInputMapper(BenchmarkSampleProperties()),
                generator,
                resultCache,
            )
        val inFlightRegistry = AnalysisJobInFlightRegistry(lifecycleService)
        val worker =
            AnalysisJobWorker(
                lifecycleService,
                playerAnalysisService,
                AnalysisJobFailureCodeMapper(),
                inFlightRegistry,
            )
        val workerExecutor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                setQueueCapacity(1)
                setThreadNamePrefix("workflow-analysis-job-")
                initialize()
            }
        val analysisJobService =
            AnalysisJobService(
                lifecycleService,
                AnalysisJobDispatcher(workerExecutor, worker),
                inFlightRegistry,
            )
        val pollingService =
            NewRankedMatchAnalysisPollingService(
                trackedPlayerAutomationService,
                automationExecutionService,
                riotMatchClient,
                playerService,
                analysisJobService,
                AnalysisAutomationProperties(),
                clock,
                AutomationObservationRecorder(SimpleMeterRegistry()),
                RiotApiCooldown(RiotApiProperties(key = "test-api-key"), clock),
            )

        try {
            `when`(playerService.findByRiotId(GAME_NAME, TAG_LINE)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
            `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, 420)).thenReturn(listOf("KR_100"))

            val automation = registrationService.register(GAME_NAME, TAG_LINE)

            assertEquals("KR_100", automation.lastSeenMatchId)
            assertEquals(0, automationExecutionJpaRepository.count())

            benchmarkSampleJpaRepository.saveAllAndFlush(availableBenchmarkSamples().map(BenchmarkSample::toEntity))
            stubComparisonContext()
            `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, 420)).thenReturn(listOf("KR_101", "KR_100"))
            `when`(playerService.findByPuuid(PUUID)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))

            assertEquals(AutomationPollOutcome.TRIGGERED, pollingService.poll(automation.id))

            val execution =
                assertNotNull(
                    automationExecutionJpaRepository.findByAutomationIdAndDetectedMatchId(automation.id, "KR_101"),
                )
            val jobId = assertNotNull(execution.analysisJobId)
            assertTrue(generator.awaitStarted())
            assertEquals(AutomationExecutionStatus.TRIGGERED, execution.status)
            assertEquals(AnalysisJobStatus.RUNNING, queryService.find(jobId).status)
            assertTrue(requireNotNull(generator.executionThreadName).startsWith("workflow-analysis-job-"))
            assertEquals(1, assertNotNull(generator.input).comparisons.size)
            assertEquals(BenchmarkScope.POSITION, assertNotNull(generator.input).comparisons.single().scope)
            assertEquals(30, assertNotNull(generator.input).comparisons.single().benchmarkSampleCount)

            generator.release()
            val job = awaitTerminal(jobId)

            assertEquals(AnalysisJobStatus.SUCCEEDED, job.status)
            assertEquals(RESULT, job.result)
            assertEquals(1, generator.calls)

            assertEquals(AutomationPollOutcome.NO_NEW_MATCH, pollingService.poll(automation.id))
            assertEquals(1, automationExecutionJpaRepository.count())
            assertEquals(1, generator.calls)
            verify(playerService, times(1)).findByPuuid(PUUID)
        } finally {
            generator.release()
            workerExecutor.shutdown()
        }
    }

    private fun awaitTerminal(jobId: java.util.UUID): AnalysisJobView {
        repeat(100) {
            val job = queryService.find(jobId)
            if (job.status == AnalysisJobStatus.SUCCEEDED || job.status == AnalysisJobStatus.FAILED) {
                return job
            }
            Thread.sleep(10)
        }
        error("Workflow test job did not reach a terminal state within one second")
    }

    private fun stubComparisonContext() {
        `when`(playerComparisonContextService.buildContext(GAME_NAME, TAG_LINE, 0, 20))
            .thenReturn(
                PlayerComparisonContext(
                    player = PlayerComparisonContextPlayer(GAME_NAME, TAG_LINE),
                    targetPuuid = PUUID,
                    rankContext = PlayerRankContext("GOLD", "I", NOW),
                    sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 5),
                    positionStatistics =
                        listOf(
                            PlayerPositionStatistics(
                                position = "MIDDLE",
                                games = 5,
                                wins = 3,
                                winRate = 0.6,
                                averageKda = 7.2,
                                averageCsPerMinute = 72.0,
                                averageGoldPerMinute = 720.0,
                                averageDamagePerMinute = 7_200.0,
                                averageVisionPerMinute = 0.72,
                                averageKillParticipation = 0.072,
                                averageDamageShare = 0.0072,
                            ),
                        ),
                    championPositionStatistics = emptyList(),
                ),
            )
    }

    private fun availableBenchmarkSamples(): List<BenchmarkSample> =
        (1..30).map { index ->
            BenchmarkSample(
                matchId = "KR_workflow-$index",
                puuid = "benchmark-player-${index % 10}",
                region = "KR",
                queueId = 420,
                tier = "GOLD",
                division = "I",
                rankCapturedAt = NOW,
                championId = 103,
                position = "MIDDLE",
                gameVersion = "16.18.1",
                gameStartTimestamp = NOW.minusSeconds(60),
                kills = index,
                deaths = 1,
                assists = index,
                kda = index.toDouble(),
                csPerMinute = index.toDouble() * 10.0,
                goldPerMinute = index.toDouble() * 100.0,
                damagePerMinute = index.toDouble() * 1_000.0,
                visionPerMinute = index.toDouble() * 0.1,
                killParticipation = index.toDouble() * 0.01,
                damageShare = index.toDouble() * 0.001,
                collectedAt = NOW,
            )
        }

    private class InMemoryResultCache : PlayerAnalysisResultCache {
        private val values = mutableMapOf<PlayerAnalysisInput, PlayerAnalysisResult>()

        override fun find(input: PlayerAnalysisInput): PlayerAnalysisResult? = values[input]

        override fun store(
            input: PlayerAnalysisInput,
            result: PlayerAnalysisResult,
        ) {
            values[input] = result
        }
    }

    private class BlockingGenerator : PlayerAnalysisGenerator {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        var calls = 0
            private set
        var input: PlayerAnalysisInput? = null
            private set
        var executionThreadName: String? = null
            private set

        override fun generate(input: PlayerAnalysisInput): PlayerAnalysisResult {
            calls++
            this.input = input
            executionThreadName = Thread.currentThread().name
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Workflow test generator was not released" }
            return RESULT
        }

        fun awaitStarted(): Boolean = started.await(5, TimeUnit.SECONDS)

        fun release() {
            release.countDown()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ContextServiceStubConfiguration {
        @Bean
        fun playerComparisonContextService(): PlayerComparisonContextService = mock(PlayerComparisonContextService::class.java)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private companion object {
        const val GAME_NAME = "Automation Player"
        const val TAG_LINE = "TEST"
        const val PUUID = "automation-test-puuid"
        val NOW: Instant = Instant.parse("2026-09-19T00:00:00Z")
        val RESULT = PlayerAnalysisResult("automation analysis result", emptyList(), emptyList(), emptyList(), emptyList())

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
