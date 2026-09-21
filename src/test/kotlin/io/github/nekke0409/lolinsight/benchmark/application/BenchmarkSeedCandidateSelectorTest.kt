package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.SampledRankedPlayer
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class BenchmarkSeedCandidateSelectorTest {
    private val selector = BenchmarkSeedCandidateSelector()

    @Test
    fun `selects zero valid sample candidates before candidates with existing samples`() {
        val selection = selector.select(listOf(player("a"), player("b"), player("c")), mapOf("a" to 3L, "b" to 0L, "c" to 1L), 3)

        assertEquals(listOf("b", "c", "a"), selection.players.map(SampledRankedPlayer::puuid))
        assertEquals(1, selection.selectedZeroValidSamplePlayers)
        assertEquals(2, selection.selectedExistingValidSamplePlayers)
    }

    @Test
    fun `fills remaining capacity with the lowest positive sample counts`() {
        val selection =
            selector.select(
                listOf(player("a"), player("b"), player("c"), player("d")),
                mapOf(
                    "a" to 8L,
                    "b" to 0L,
                    "c" to 2L,
                    "d" to 1L,
                ),
                3,
            )

        assertEquals(listOf("b", "d", "c"), selection.players.map(SampledRankedPlayer::puuid))
    }

    @Test
    fun `orders all positive counts then PUUID deterministically regardless of input order`() {
        val candidates = listOf(player("d"), player("a"), player("c"), player("b"))
        val counts = mapOf("a" to 2L, "b" to 2L, "c" to 1L, "d" to 1L)

        val first = selector.select(candidates, counts, 4)
        val second = selector.select(candidates.reversed(), counts, 4)

        assertEquals(listOf("c", "d", "a", "b"), first.players.map(SampledRankedPlayer::puuid))
        assertEquals(first, second)
    }

    @Test
    fun `deduplicates candidates and applies the limit after priority calculation`() {
        val selection =
            selector.select(
                listOf(player("high"), player("high"), player("low"), player("zero")),
                mapOf(
                    "high" to 10L,
                    "low" to 1L,
                    "zero" to 0L,
                ),
                2,
            )

        assertEquals(listOf("zero", "low"), selection.players.map(SampledRankedPlayer::puuid))
        assertEquals(1, selection.selectedZeroValidSamplePlayers)
        assertEquals(1, selection.selectedExistingValidSamplePlayers)
    }

    @Test
    fun `returns an empty selection for empty candidates`() {
        val selection = selector.select(emptyList(), emptyMap(), 5)

        assertEquals(emptyList(), selection.players)
        assertEquals(0, selection.selectedZeroValidSamplePlayers)
        assertEquals(0, selection.selectedExistingValidSamplePlayers)
    }

    private fun player(puuid: String): SampledRankedPlayer =
        SampledRankedPlayer(
            puuid = puuid,
            region = "KR",
            queue = "RANKED_SOLO_5x5",
            tier = "GOLD",
            division = "I",
            rankCapturedAt = Instant.parse("2026-09-21T00:00:00Z"),
        )
}
