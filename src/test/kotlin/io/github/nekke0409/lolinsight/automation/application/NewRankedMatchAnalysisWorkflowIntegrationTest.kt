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
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobWorker
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.observability.AutomationObservationRecorder
import io.github.nekke0409.lolinsight.automation.persistence.AutomationExecutionJpaRepository
import io.github.nekke0409.lolinsight.automation.scheduling.AnalysisAutomationProperties
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSampleProperties
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.comparison.application.MetricComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeature
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
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
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    private lateinit var clock: Clock

    @Test
    fun `registration detection and a repeated poll produce one persisted completed analysis job`() {
        val riotMatchClient = mock(RiotMatchClient::class.java)
        val playerService = mock(PlayerService::class.java)
        val comparisonFeatureService = mock(PlayerComparisonFeatureService::class.java)
        val resultCache = InMemoryResultCache()
        val generator = CountingGenerator()
        val registrationService =
            TrackedPlayerAutomationRegistrationService(
                playerService,
                riotMatchClient,
                trackedPlayerAutomationService,
                clock,
            )
        val playerAnalysisService =
            PlayerAnalysisService(
                comparisonFeatureService,
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
        val analysisJobService =
            AnalysisJobService(
                lifecycleService,
                AnalysisJobDispatcher(SyncTaskExecutor(), worker),
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

        `when`(playerService.findByRiotId(GAME_NAME, TAG_LINE)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, 420)).thenReturn(listOf("KR_100"))

        val automation = registrationService.register(GAME_NAME, TAG_LINE)

        assertEquals("KR_100", automation.lastSeenMatchId)
        assertEquals(0, automationExecutionJpaRepository.count())

        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, 420)).thenReturn(listOf("KR_101", "KR_100"))
        `when`(playerService.findByPuuid(PUUID)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
        `when`(comparisonFeatureService.buildFeature(GAME_NAME, TAG_LINE, 0, 20)).thenReturn(FEATURE)

        assertEquals(AutomationPollOutcome.TRIGGERED, pollingService.poll(automation.id))

        val execution =
            assertNotNull(
                automationExecutionJpaRepository.findByAutomationIdAndDetectedMatchId(automation.id, "KR_101"),
            )
        val job = queryService.find(assertNotNull(execution.analysisJobId))
        assertEquals(AutomationExecutionStatus.TRIGGERED, execution.status)
        assertEquals(AnalysisJobStatus.SUCCEEDED, job.status)
        assertEquals(RESULT, job.result)
        assertEquals(1, generator.calls)

        assertEquals(AutomationPollOutcome.NO_NEW_MATCH, pollingService.poll(automation.id))
        assertEquals(1, automationExecutionJpaRepository.count())
        assertEquals(1, generator.calls)
        verify(playerService, times(1)).findByPuuid(PUUID)
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

    private class CountingGenerator : PlayerAnalysisGenerator {
        var calls = 0
            private set

        override fun generate(input: PlayerAnalysisInput): PlayerAnalysisResult {
            calls++
            return RESULT
        }
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
        val RESULT = PlayerAnalysisResult("자동화 분석 결과", emptyList(), emptyList(), emptyList(), emptyList())
        val METRIC = MetricComparison(7.2, 6.7, 6.8, 0.5, 0.4, 6.3, 7.0, 7.4)
        val FEATURE =
            PlayerComparisonFeature(
                PlayerRankContext("GOLD", "I", NOW),
                listOf(
                    PlayerCohortComparison(
                        scope = BenchmarkScope.POSITION,
                        position = "MIDDLE",
                        championId = null,
                        userGames = 5,
                        status = PlayerCohortComparisonStatus.AVAILABLE,
                        benchmarkCohort = BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
                        benchmarkSampleCount = 30,
                        benchmarkUniquePlayerCount = 10,
                        metrics =
                            PlayerComparisonMetrics(
                                METRIC,
                                METRIC,
                                METRIC,
                                METRIC,
                                METRIC,
                                METRIC,
                                METRIC,
                            ),
                    ),
                ),
            )

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
