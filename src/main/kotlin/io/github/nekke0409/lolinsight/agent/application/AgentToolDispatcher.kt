package io.github.nekke0409.lolinsight.agent.application

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.nekke0409.lolinsight.comparison.application.PlayerChampionPositionStatistics
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContext
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
import io.github.nekke0409.lolinsight.comparison.application.PlayerScopeStatistics
import io.github.nekke0409.lolinsight.rag.application.PatchNoteCitation
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidence
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidenceSelector
import io.github.nekke0409.lolinsight.rag.application.PatchNoteRetrievalService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSearchRequest
import io.github.nekke0409.lolinsight.rag.application.RagException
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

interface AgentToolExecutor {
    fun newContext(
        gameName: String,
        tagLine: String,
    ): AgentToolExecutionContext

    fun newContext(
        gameName: String,
        tagLine: String,
        knowledgeScope: AgentPatchNoteScope?,
    ): AgentToolExecutionContext = newContext(gameName, tagLine)

    fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
    ): AgentToolDispatchResult

    fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
        timeout: Duration,
    ): AgentToolDispatchResult = dispatch(call, context)

    fun serialize(result: AgentToolDispatchResult): String

    fun callSignature(call: AgentModelToolCall): String
}

@Component
class AgentToolDispatcher(
    private val objectMapper: ObjectMapper,
    private val playerComparisonContextService: PlayerComparisonContextService,
    private val playerComparisonFeatureService: PlayerComparisonFeatureService,
    private val properties: AgentQuestionProperties = AgentQuestionProperties(),
    @Autowired(required = false) private val patchNoteRetrievalService: PatchNoteRetrievalService? = null,
) : AgentToolExecutor {
    override fun newContext(
        gameName: String,
        tagLine: String,
    ): AgentToolExecutionContext = newContext(gameName, tagLine, null)

    override fun newContext(
        gameName: String,
        tagLine: String,
        knowledgeScope: AgentPatchNoteScope?,
    ): AgentToolExecutionContext =
        AgentToolExecutionContext(
            gameName = gameName,
            tagLine = tagLine,
            playerComparisonContextService = playerComparisonContextService,
            knowledgeScope = knowledgeScope,
        )

    override fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
    ): AgentToolDispatchResult = dispatch(call, context, Duration.ofSeconds(60))

    override fun dispatch(
        call: AgentModelToolCall,
        context: AgentToolExecutionContext,
        timeout: Duration,
    ): AgentToolDispatchResult {
        if (!context.allows(call.name)) {
            return AgentToolDispatchResult.failed(observedAgentToolName(call.name), "UNSUPPORTED_TOOL", UNSUPPORTED_TOOL_MESSAGE)
        }

        return when (call.name) {
            GET_RANKED_STATS,
            GET_PEER_COMPARISON,
            -> {
                val groupBy =
                    call.parseGroupByOrNull()
                        ?: return AgentToolDispatchResult.failed(
                            observedAgentToolName(call.name),
                            "INVALID_TOOL_ARGUMENTS",
                            INVALID_ARGUMENTS_MESSAGE,
                        )
                if (call.name == GET_RANKED_STATS) rankedStats(groupBy, context) else peerComparison(groupBy, context)
            }

            SEARCH_PATCH_NOTES -> {
                val query =
                    call.parsePatchNoteQueryOrNull()
                        ?: return AgentToolDispatchResult.failed(
                            SEARCH_PATCH_NOTES,
                            "INVALID_TOOL_ARGUMENTS",
                            PATCH_NOTE_INVALID_ARGUMENTS_MESSAGE,
                        )
                searchPatchNotes(query, context, timeout)
            }

            else -> AgentToolDispatchResult.failed(observedAgentToolName(call.name), "UNSUPPORTED_TOOL", UNSUPPORTED_TOOL_MESSAGE)
        }
    }

    override fun serialize(result: AgentToolDispatchResult): String = objectMapper.writeValueAsString(result.payload)

    override fun callSignature(call: AgentModelToolCall): String =
        when (call.name) {
            GET_RANKED_STATS,
            GET_PEER_COMPARISON,
            -> call.parseGroupByOrNull()?.let { call.name + '\u0000' + it.name } ?: call.name + '\u0000' + call.arguments

            SEARCH_PATCH_NOTES ->
                call.parsePatchNoteQueryOrNull()?.let { call.name + '\u0000' + it }
                    ?: call.name + '\u0000' + call.arguments
            else -> call.name + '\u0000' + call.arguments
        }

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
                metricUnits = AgentMetricUnits(),
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
                metricUnits = AgentMetricUnits(),
                limitations = limitations,
            )
        return AgentToolDispatchResult.succeeded(GET_PEER_COMPARISON, payload, limitations)
    }

    private fun searchPatchNotes(
        query: String,
        executionContext: AgentToolExecutionContext,
        timeout: Duration,
    ): AgentToolDispatchResult {
        if (!executionContext.beginPatchNoteSearch()) {
            return AgentToolDispatchResult.failed(
                SEARCH_PATCH_NOTES,
                "PATCH_NOTE_SEARCH_LIMIT_REACHED",
                "한 요청에서 패치 노트 검색은 한 번만 실행할 수 있습니다.",
            )
        }
        val scope = checkNotNull(executionContext.knowledgeScope)
        val retrieval =
            patchNoteRetrievalService
                ?: throw AgentPatchNoteConfigurationException(
                    "agent.patch-notes.enabled requires rag.enabled=true and PatchNoteRetrievalService",
                )
        val execution =
            try {
                retrieval.searchWithExecution(
                    PatchNoteSearchRequest(query, scope.patchVersion, scope.locale, properties.patchNotes.topK),
                    timeout,
                )
            } catch (exception: RagException) {
                throw AgentPatchNoteRetrievalException(exception)
            }
        if (execution.results.any { it.patchVersion != scope.patchVersion || it.locale != scope.locale }) {
            throw AgentPatchNoteRetrievalException(IllegalStateException("patch-note retrieval escaped the request scope"))
        }

        val resultCount = execution.results.size
        val evidence =
            PatchNoteEvidenceSelector.select(
                execution.results,
                properties.patchNotes.maxEvidenceCharacters,
                PATCH_NOTE_EVIDENCE_PREFIX,
            )
        val limitations =
            when {
                resultCount == 0 -> listOf("저장된 요청 범위에서 답변 근거를 찾지 못했습니다.")
                evidence.isEmpty() -> listOf("검색된 근거가 Agent Tool 전달 한도 안에 포함되지 않았습니다.")
                else -> emptyList()
            }
        val status =
            when {
                resultCount == 0 -> AgentPatchNoteSearchStatus.NO_EVIDENCE
                evidence.isEmpty() -> AgentPatchNoteSearchStatus.INSUFFICIENT_EVIDENCE
                else -> AgentPatchNoteSearchStatus.AVAILABLE
            }
        val payload =
            AgentPatchNoteSearchToolResult(
                status = status,
                patchVersion = scope.patchVersion,
                locale = scope.locale,
                resultCount = resultCount,
                evidence = evidence.map(AgentPatchNoteToolEvidence::from),
                limitations = limitations,
            )
        return AgentToolDispatchResult.succeeded(
            toolName = SEARCH_PATCH_NOTES,
            payload = payload,
            limitations = limitations,
            patchNoteEvidence = evidence,
            patchNoteSearchExecution =
                AgentPatchNoteSearchExecution(
                    queryEmbeddingAttempted = execution.queryEmbeddingAttempted,
                    queryEmbeddingInputTokens = execution.queryEmbeddingInputTokens,
                    resultCount = resultCount,
                ),
        )
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

    private fun AgentModelToolCall.parsePatchNoteQueryOrNull(): String? =
        runCatching {
            val root = objectMapper.readTree(arguments)
            if (!root.isObject || root.size() != 1 || !root.has("query")) {
                return@runCatching null
            }
            val query = root.get("query")
            if (!query.isTextual) {
                return@runCatching null
            }
            query.asText().takeIf { it.isNotBlank() && it.length <= properties.maxQuestionCharacters }
        }.getOrNull()

    private fun PlayerComparisonContext.statistics(groupBy: AgentStatsGroupBy): List<PlayerScopeStatistics> =
        when (groupBy) {
            AgentStatsGroupBy.POSITION -> positionStatistics
            AgentStatsGroupBy.CHAMPION_POSITION -> championPositionStatistics
        }

    private companion object {
        const val INVALID_ARGUMENTS_MESSAGE = "허용된 groupBy enum 하나만 포함한 JSON object가 필요합니다."
        const val PATCH_NOTE_INVALID_ARGUMENTS_MESSAGE = "비어 있지 않은 query 하나만 포함한 JSON object가 필요합니다."
        const val UNSUPPORTED_TOOL_MESSAGE = "이 Agent에는 요청한 Tool이 없습니다."
        const val PATCH_NOTE_EVIDENCE_PREFIX = "PATCH_E"
    }
}

internal const val GET_RANKED_STATS = "get_ranked_stats"
internal const val GET_PEER_COMPARISON = "get_peer_comparison"
internal const val SEARCH_PATCH_NOTES = "search_patch_notes"

internal fun observedAgentToolName(name: String): String =
    when (name) {
        GET_RANKED_STATS,
        GET_PEER_COMPARISON,
        SEARCH_PATCH_NOTES,
        -> name

        else -> "UNSUPPORTED_TOOL"
    }

enum class AgentStatsGroupBy {
    POSITION,
    CHAMPION_POSITION,
}

class AgentToolExecutionContext(
    val gameName: String,
    val tagLine: String,
    private val playerComparisonContextService: PlayerComparisonContextService,
    val knowledgeScope: AgentPatchNoteScope? = null,
) {
    val allowedToolNames: Set<String> =
        buildSet {
            add(GET_RANKED_STATS)
            add(GET_PEER_COMPARISON)
            if (knowledgeScope != null) add(SEARCH_PATCH_NOTES)
        }

    private val context: PlayerComparisonContext by lazy {
        playerComparisonContextService.buildContext(gameName, tagLine, start = 0, count = RANKED_SOLO_MATCH_LIMIT)
    }
    private var patchNoteSearchStarted = false
    private val deliveredPatchNoteEvidence = linkedMapOf<String, PatchNoteEvidence>()

    fun comparisonContext(): PlayerComparisonContext = context

    fun allows(toolName: String): Boolean = toolName in allowedToolNames

    fun beginPatchNoteSearch(): Boolean =
        if (patchNoteSearchStarted) {
            false
        } else {
            patchNoteSearchStarted = true
            true
        }

    fun acceptDeliveredPatchNoteEvidence(result: AgentToolDispatchResult) {
        if (!result.success) return
        result.patchNoteEvidence.forEach { evidence -> deliveredPatchNoteEvidence[evidence.evidenceId] = evidence }
    }

    fun citationsFor(evidenceIds: List<String>): List<PatchNoteCitation>? =
        evidenceIds.map { deliveredPatchNoteEvidence[it]?.toCitation() ?: return null }

    private companion object {
        const val RANKED_SOLO_MATCH_LIMIT = 20
    }
}

data class AgentToolDispatchResult(
    val toolName: String,
    val success: Boolean,
    val payload: Any,
    val limitations: List<String>,
    val patchNoteEvidence: List<PatchNoteEvidence> = emptyList(),
    val patchNoteSearchExecution: AgentPatchNoteSearchExecution? = null,
) {
    companion object {
        fun succeeded(
            toolName: String,
            payload: Any,
            limitations: List<String>,
            patchNoteEvidence: List<PatchNoteEvidence> = emptyList(),
            patchNoteSearchExecution: AgentPatchNoteSearchExecution? = null,
        ): AgentToolDispatchResult =
            AgentToolDispatchResult(toolName, true, payload, limitations, patchNoteEvidence, patchNoteSearchExecution)

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

data class AgentPatchNoteSearchExecution(
    val queryEmbeddingAttempted: Boolean,
    val queryEmbeddingInputTokens: Long?,
    val resultCount: Int,
)

data class AgentToolErrorResult(
    val status: String,
    val code: String,
    val message: String,
)

enum class AgentPatchNoteSearchStatus { AVAILABLE, NO_EVIDENCE, INSUFFICIENT_EVIDENCE }

data class AgentPatchNoteSearchToolResult(
    val status: AgentPatchNoteSearchStatus,
    val patchVersion: String,
    val locale: String,
    val resultCount: Int,
    val evidence: List<AgentPatchNoteToolEvidence>,
    val limitations: List<String>,
)

data class AgentPatchNoteToolEvidence(
    val evidenceId: String,
    val title: String,
    val headingPath: List<String>,
    val evidenceText: String,
) {
    companion object {
        fun from(evidence: PatchNoteEvidence): AgentPatchNoteToolEvidence =
            AgentPatchNoteToolEvidence(evidence.evidenceId, evidence.title, evidence.headingPath, evidence.evidenceText)
    }
}

data class AgentRankedStatsToolResult(
    val status: String,
    val scope: String,
    val sample: AgentSampleResult,
    val statistics: List<AgentScopedStatisticsResult>,
    val metricUnits: AgentMetricUnits,
    val limitations: List<String>,
)

data class AgentSampleResult(
    val requestedMatchCount: Int,
    val analyzedMatchCount: Int,
)

data class AgentScopedStatisticsResult(
    val scope: String,
    val position: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val champion: AgentChampionReference?,
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
                champion =
                    (statistics as? PlayerChampionPositionStatistics)
                        ?.let { AgentChampionReference(it.championId) },
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

data class AgentChampionReference(
    val championId: Int,
)

data class AgentMetricUnits(
    val winRate: String = "ratio_0_to_1",
    val kda: String = "ratio",
    val csPerMinute: String = "cs_per_minute",
    val goldPerMinute: String = "gold_per_minute",
    val damagePerMinute: String = "damage_per_minute",
    val visionPerMinute: String = "vision_per_minute",
    val killParticipation: String = "ratio_0_to_1",
    val damageShare: String = "ratio_0_to_1",
)

data class AgentPeerComparisonToolResult(
    val status: String,
    val requestedScope: String,
    val playerRank: AgentPlayerRankResult?,
    val comparisons: List<AgentPeerComparisonResult>,
    val metricUnits: AgentMetricUnits,
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
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val champion: AgentChampionReference?,
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
                champion = comparison.championId?.let(::AgentChampionReference),
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
