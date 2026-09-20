package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContext
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
import io.github.nekke0409.lolinsight.comparison.application.PlayerScopeStatistics
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

interface AgentToolExecutor {
    fun newContext(
        gameName: String,
        tagLine: String,
    ): AgentToolExecutionContext

    fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
    ): AgentToolDispatchResult

    fun serialize(result: AgentToolDispatchResult): String

    fun callSignature(call: AgentModelToolCall): String
}

@Component
class AgentToolDispatcher(
    private val objectMapper: ObjectMapper,
    private val playerComparisonContextService: PlayerComparisonContextService,
    private val playerComparisonFeatureService: PlayerComparisonFeatureService,
) : AgentToolExecutor {
    override fun newContext(
        gameName: String,
        tagLine: String,
    ): AgentToolExecutionContext =
        AgentToolExecutionContext(
            gameName = gameName,
            tagLine = tagLine,
            playerComparisonContextService = playerComparisonContextService,
        )

    override fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
    ): AgentToolDispatchResult {
        val groupBy =
            call.parseGroupByOrNull()
                ?: return AgentToolDispatchResult.failed(call.name, "INVALID_TOOL_ARGUMENTS", INVALID_ARGUMENTS_MESSAGE)

        return when (call.name) {
            GET_RANKED_STATS -> rankedStats(groupBy, context)
            GET_PEER_COMPARISON -> peerComparison(groupBy, context)
            else -> AgentToolDispatchResult.failed(call.name, "UNSUPPORTED_TOOL", UNSUPPORTED_TOOL_MESSAGE)
        }
    }

    override fun serialize(result: AgentToolDispatchResult): String = objectMapper.writeValueAsString(result.payload)

    override fun callSignature(call: AgentModelToolCall): String =
        call
            .parseGroupByOrNull()
            ?.let { call.name + '\u0000' + it.name }
            ?: call.name + '\u0000' + call.arguments

    private fun rankedStats(
        groupBy: AgentStatsGroupBy,
        executionContext: AgentToolExecutionContext,
    ): AgentToolDispatchResult {
        val context = executionContext.comparisonContext()
        val statistics = context.statistics(groupBy)
        val limitations =
            buildList {
                if (context.sample.analyzedCount == 0) {
                    add("최근 Ranked Solo 경기 상세 정보를 분석할 수 없습니다.")
                }
            }
        val payload =
            AgentRankedStatsToolResult(
                status = "AVAILABLE",
                scope = groupBy.name,
                sample = AgentSampleResult(context.sample.requestedCount, context.sample.analyzedCount),
                statistics = statistics.map { AgentScopedStatisticsResult.from(groupBy, it) },
                limitations = limitations,
            )
        return AgentToolDispatchResult.succeeded(GET_RANKED_STATS, payload, limitations)
    }

    private fun peerComparison(
        groupBy: AgentStatsGroupBy,
        executionContext: AgentToolExecutionContext,
    ): AgentToolDispatchResult {
        val context = executionContext.comparisonContext()
        val feature = playerComparisonFeatureService.buildFeature(context)
        val comparisons = feature.comparisons.filter { it.scope.name == groupBy.name }
        val limitations = comparisons.mapNotNull(AgentPeerComparisonResult::limitation).distinct()
        val payload =
            AgentPeerComparisonToolResult(
                status = "AVAILABLE",
                requestedScope = groupBy.name,
                playerRank = feature.rankContext?.let(AgentPlayerRankResult::from),
                comparisons = comparisons.map(AgentPeerComparisonResult::from),
                limitations = limitations,
            )
        return AgentToolDispatchResult.succeeded(GET_PEER_COMPARISON, payload, limitations)
    }

    private fun AgentModelToolCall.parseGroupByOrNull(): AgentStatsGroupBy? =
        runCatching {
            val root = objectMapper.readTree(arguments)
            if (!root.isObject || root.size() != 1 || !root.has("groupBy")) {
                return@runCatching null
            }
            val groupBy = root.get("groupBy")
            if (!groupBy.isTextual) {
                return@runCatching null
            }
            AgentStatsGroupBy.entries.singleOrNull { it.name == groupBy.asText() }
        }.getOrNull()

    private fun PlayerComparisonContext.statistics(groupBy: AgentStatsGroupBy): List<PlayerScopeStatistics> =
        when (groupBy) {
            AgentStatsGroupBy.POSITION -> positionStatistics
            AgentStatsGroupBy.CHAMPION_POSITION -> championPositionStatistics
        }

    private companion object {
        const val GET_RANKED_STATS = "get_ranked_stats"
        const val GET_PEER_COMPARISON = "get_peer_comparison"
        const val INVALID_ARGUMENTS_MESSAGE = "허용된 groupBy enum 하나만 포함한 JSON object가 필요합니다."
        const val UNSUPPORTED_TOOL_MESSAGE = "이 Agent에는 요청한 Tool이 없습니다."
    }
}

enum class AgentStatsGroupBy {
    POSITION,
    CHAMPION_POSITION,
}

class AgentToolExecutionContext(
    val gameName: String,
    val tagLine: String,
    private val playerComparisonContextService: PlayerComparisonContextService,
) {
    private val context: PlayerComparisonContext by lazy {
        playerComparisonContextService.buildContext(gameName, tagLine, start = 0, count = RANKED_SOLO_MATCH_LIMIT)
    }

    fun comparisonContext(): PlayerComparisonContext = context

    private companion object {
        const val RANKED_SOLO_MATCH_LIMIT = 20
    }
}

data class AgentToolDispatchResult(
    val toolName: String,
    val success: Boolean,
    val payload: Any,
    val limitations: List<String>,
) {
    companion object {
        fun succeeded(
            toolName: String,
            payload: Any,
            limitations: List<String>,
        ): AgentToolDispatchResult = AgentToolDispatchResult(toolName, true, payload, limitations)

        fun failed(
            toolName: String,
            code: String,
            message: String,
        ): AgentToolDispatchResult =
            AgentToolDispatchResult(
                toolName = toolName,
                success = false,
                payload = AgentToolErrorResult(status = "REJECTED", code = code, message = message),
                limitations = listOf(message),
            )
    }
}

data class AgentToolErrorResult(
    val status: String,
    val code: String,
    val message: String,
)

data class AgentRankedStatsToolResult(
    val status: String,
    val scope: String,
    val sample: AgentSampleResult,
    val statistics: List<AgentScopedStatisticsResult>,
    val limitations: List<String>,
)

data class AgentSampleResult(
    val requestedMatchCount: Int,
    val analyzedMatchCount: Int,
)

data class AgentScopedStatisticsResult(
    val scope: String,
    val position: String,
    val games: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val averageKda: Double,
    val averageCsPerMinute: Double,
    val averageGoldPerMinute: Double,
    val averageDamagePerMinute: Double,
    val averageVisionPerMinute: Double,
    val averageKillParticipation: Double,
    val averageDamageShare: Double,
) {
    companion object {
        fun from(
            groupBy: AgentStatsGroupBy,
            statistics: PlayerScopeStatistics,
        ): AgentScopedStatisticsResult =
            AgentScopedStatisticsResult(
                scope = groupBy.name,
                position = statistics.position,
                games = statistics.games,
                wins = statistics.wins,
                losses = statistics.games - statistics.wins,
                winRate = statistics.winRate,
                averageKda = statistics.averageKda,
                averageCsPerMinute = statistics.averageCsPerMinute,
                averageGoldPerMinute = statistics.averageGoldPerMinute,
                averageDamagePerMinute = statistics.averageDamagePerMinute,
                averageVisionPerMinute = statistics.averageVisionPerMinute,
                averageKillParticipation = statistics.averageKillParticipation,
                averageDamageShare = statistics.averageDamageShare,
            )
    }
}

data class AgentPeerComparisonToolResult(
    val status: String,
    val requestedScope: String,
    val playerRank: AgentPlayerRankResult?,
    val comparisons: List<AgentPeerComparisonResult>,
    val limitations: List<String>,
)

data class AgentPlayerRankResult(
    val tier: String,
    val division: String,
) {
    companion object {
        fun from(rank: PlayerRankContext): AgentPlayerRankResult = AgentPlayerRankResult(rank.tier, rank.division)
    }
}

data class AgentPeerComparisonResult(
    val scope: String,
    val position: String,
    val userGames: Int,
    val status: String,
    val benchmark: AgentBenchmarkScopeResult?,
    val metrics: AgentComparisonMetricsResult?,
) {
    companion object {
        fun from(comparison: PlayerCohortComparison): AgentPeerComparisonResult =
            AgentPeerComparisonResult(
                scope = comparison.scope.name,
                position = comparison.position,
                userGames = comparison.userGames,
                status = comparison.status.name,
                benchmark =
                    comparison.benchmarkCohort?.let {
                        AgentBenchmarkScopeResult(
                            tier = it.tier,
                            division = it.division,
                            position = it.position,
                            sampleCount = comparison.benchmarkSampleCount,
                            uniquePlayerCount = comparison.benchmarkUniquePlayerCount,
                        )
                    },
                metrics = comparison.metrics?.let(AgentComparisonMetricsResult::from),
            )

        fun limitation(comparison: PlayerCohortComparison): String? =
            when (comparison.status) {
                PlayerCohortComparisonStatus.AVAILABLE -> null
                PlayerCohortComparisonStatus.UNRANKED -> "현재 Ranked Solo rank 정보가 없어 peer benchmark를 비교할 수 없습니다."
                PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE ->
                    "${comparison.scope} ${comparison.position} 표본 경기 수가 부족하여 비교하지 않았습니다."

                PlayerCohortComparisonStatus.BENCHMARK_NO_DATA ->
                    "${comparison.scope} ${comparison.position} peer benchmark 표본이 없습니다."

                PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE ->
                    "${comparison.scope} ${comparison.position} peer benchmark 표본이 충분하지 않습니다."
            }
    }
}

data class AgentBenchmarkScopeResult(
    val tier: String,
    val division: String,
    val position: String,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
)

data class AgentComparisonMetricsResult(
    val kda: AgentMetricComparisonResult,
    val csPerMinute: AgentMetricComparisonResult,
    val goldPerMinute: AgentMetricComparisonResult,
    val damagePerMinute: AgentMetricComparisonResult,
    val visionPerMinute: AgentMetricComparisonResult,
    val killParticipation: AgentMetricComparisonResult,
    val damageShare: AgentMetricComparisonResult,
) {
    companion object {
        fun from(metrics: PlayerComparisonMetrics): AgentComparisonMetricsResult =
            AgentComparisonMetricsResult(
                kda = AgentMetricComparisonResult.from(metrics.kda),
                csPerMinute = AgentMetricComparisonResult.from(metrics.csPerMinute),
                goldPerMinute = AgentMetricComparisonResult.from(metrics.goldPerMinute),
                damagePerMinute = AgentMetricComparisonResult.from(metrics.damagePerMinute),
                visionPerMinute = AgentMetricComparisonResult.from(metrics.visionPerMinute),
                killParticipation = AgentMetricComparisonResult.from(metrics.killParticipation),
                damageShare = AgentMetricComparisonResult.from(metrics.damageShare),
            )
    }
}

data class AgentMetricComparisonResult(
    val playerValue: Double,
    val benchmarkMean: Double,
    val benchmarkMedian: Double,
    val differenceFromMean: Double,
    val differenceFromMedian: Double,
    val benchmarkP25: Double,
    val benchmarkP75: Double,
    val benchmarkP90: Double,
) {
    companion object {
        fun from(metric: io.github.nekke0409.lolinsight.comparison.application.MetricComparison): AgentMetricComparisonResult =
            AgentMetricComparisonResult(
                playerValue = metric.playerValue,
                benchmarkMean = metric.benchmarkMean,
                benchmarkMedian = metric.benchmarkMedian,
                differenceFromMean = metric.differenceFromMean,
                differenceFromMedian = metric.differenceFromMedian,
                benchmarkP25 = metric.benchmarkP25,
                benchmarkP75 = metric.benchmarkP75,
                benchmarkP90 = metric.benchmarkP90,
            )
    }
}
