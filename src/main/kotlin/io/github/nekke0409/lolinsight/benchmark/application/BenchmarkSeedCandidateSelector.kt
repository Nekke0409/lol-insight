package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import org.springframework.stereotype.Component

/** Deterministically prioritizes bounded discovery candidates before match collection starts. */
@Component
class BenchmarkSeedCandidateSelector {
    fun select(
        candidates: Collection<SampledRankedPlayer>,
        validSampleCounts: Map<String, Long>,
        playerLimit: Int,
    ): BenchmarkSeedCandidateSelection {
        require(playerLimit > 0) { "playerLimit must be positive" }
        require(validSampleCounts.values.all { it >= 0 }) { "validSampleCounts cannot contain negative values" }

        val selectedPlayers =
            candidates
                .associateBy(SampledRankedPlayer::puuid)
                .values
                .sortedWith(
                    compareBy<SampledRankedPlayer> { player -> validSampleCounts[player.puuid] ?: 0L }
                        .thenBy(SampledRankedPlayer::puuid),
                ).take(playerLimit)

        val selectedZeroValidSamplePlayers = selectedPlayers.count { (validSampleCounts[it.puuid] ?: 0L) == 0L }
        return BenchmarkSeedCandidateSelection(
            players = selectedPlayers,
            selectedZeroValidSamplePlayers = selectedZeroValidSamplePlayers,
            selectedExistingValidSamplePlayers = selectedPlayers.size - selectedZeroValidSamplePlayers,
        )
    }
}

data class BenchmarkSeedCandidateSelection(
    val players: List<SampledRankedPlayer>,
    val selectedZeroValidSamplePlayers: Int,
    val selectedExistingValidSamplePlayers: Int,
) {
    init {
        require(players.map(SampledRankedPlayer::puuid).distinct().size == players.size) {
            "players must be unique by puuid"
        }
        require(selectedZeroValidSamplePlayers >= 0) { "selectedZeroValidSamplePlayers cannot be negative" }
        require(selectedExistingValidSamplePlayers >= 0) { "selectedExistingValidSamplePlayers cannot be negative" }
        require(selectedZeroValidSamplePlayers + selectedExistingValidSamplePlayers == players.size) {
            "selected sample groups must equal players"
        }
    }
}
