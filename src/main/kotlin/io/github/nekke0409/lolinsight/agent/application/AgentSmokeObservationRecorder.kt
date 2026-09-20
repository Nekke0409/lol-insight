package io.github.nekke0409.lolinsight.agent.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Opt-in local smoke aid. It records only Tool selection and deterministic, non-identifying Ranked Solo metrics.
 */
interface AgentSmokeObservationRecorder {
    fun record(result: AgentToolDispatchResult)
}

@Component
@ConditionalOnProperty(prefix = "agent.smoke-observation", name = ["enabled"], havingValue = "true")
class SafeLoggingAgentSmokeObservationRecorder : AgentSmokeObservationRecorder {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(result: AgentToolDispatchResult) {
        val observation = result.toSmokeObservation()
        logger.info(
            "agent_smoke_tool tool={} success={} groupBy={} requestedMatches={} analyzedMatches={} statistics={} comparisons={}",
            observation.toolName,
            observation.success,
            observation.groupBy ?: "none",
            observation.requestedMatchCount,
            observation.analyzedMatchCount,
            observation.statistics,
            observation.comparisons,
        )
    }
}

internal data class AgentSmokeToolObservation(
    val toolName: String,
    val success: Boolean,
    val groupBy: String?,
    val requestedMatchCount: Int?,
    val analyzedMatchCount: Int?,
    val statistics: List<AgentSmokeStatisticsObservation>,
    val comparisons: List<AgentSmokeComparisonObservation>,
)

internal data class AgentSmokeStatisticsObservation(
    val position: String,
    val games: Int,
    val winRate: Double,
    val averageKda: Double,
    val averageCsPerMinute: Double,
)

internal data class AgentSmokeComparisonObservation(
    val scope: String,
    val position: String,
    val userGames: Int,
    val status: String,
    val tier: String?,
    val division: String?,
    val benchmarkSampleCount: Long?,
    val benchmarkUniquePlayerCount: Long?,
    val kda: AgentSmokeMetricComparisonObservation?,
    val csPerMinute: AgentSmokeMetricComparisonObservation?,
)

internal data class AgentSmokeMetricComparisonObservation(
    val playerValue: Double,
    val benchmarkMean: Double,
    val differenceFromMean: Double,
)

internal fun AgentToolDispatchResult.toSmokeObservation(): AgentSmokeToolObservation {
    val rankedStats = payload as? AgentRankedStatsToolResult
    val peerComparison = payload as? AgentPeerComparisonToolResult
    return AgentSmokeToolObservation(
        toolName = observedAgentToolName(toolName),
        success = success,
        groupBy = rankedStats?.scope ?: peerComparison?.requestedScope,
        requestedMatchCount = rankedStats?.sample?.requestedMatchCount,
        analyzedMatchCount = rankedStats?.sample?.analyzedMatchCount,
        statistics =
            rankedStats
                ?.statistics
                ?.map {
                    AgentSmokeStatisticsObservation(
                        position = it.position,
                        games = it.games,
                        winRate = it.winRate,
                        averageKda = it.averageKda,
                        averageCsPerMinute = it.averageCsPerMinute,
                    )
                }.orEmpty(),
        comparisons =
            peerComparison
                ?.comparisons
                ?.map {
                    AgentSmokeComparisonObservation(
                        scope = it.scope,
                        position = it.position,
                        userGames = it.userGames,
                        status = it.status,
                        tier = peerComparison.playerRank?.tier,
                        division = peerComparison.playerRank?.division,
                        benchmarkSampleCount = it.benchmark?.sampleCount,
                        benchmarkUniquePlayerCount = it.benchmark?.uniquePlayerCount,
                        kda = it.metrics?.kda?.toSmokeObservation(),
                        csPerMinute = it.metrics?.csPerMinute?.toSmokeObservation(),
                    )
                }.orEmpty(),
    )
}

private fun AgentMetricComparisonResult.toSmokeObservation(): AgentSmokeMetricComparisonObservation =
    AgentSmokeMetricComparisonObservation(
        playerValue = playerValue,
        benchmarkMean = benchmarkMean,
        differenceFromMean = differenceFromMean,
    )
