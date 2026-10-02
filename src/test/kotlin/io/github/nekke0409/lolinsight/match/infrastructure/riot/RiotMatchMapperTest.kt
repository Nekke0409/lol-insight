package io.github.nekke0409.lolinsight.match.infrastructure.riot

import io.github.nekke0409.lolinsight.match.application.MatchResponse
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsCalculator
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration
import java.time.Instant
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiotMatchMapperTest {
    @Test
    fun `maps synthetic Match fixture with absent atakhan`() {
        val response =
            JsonMapper.builder().addModule(KotlinModule.Builder().build()).build().readValue(
                requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail-without-atakhan.json")).readText(),
                RiotMatchResponseDto::class.java,
            )

        val match = RiotMatchMapper.toMatch(response)
        assertTrue(match.teams.all { it.objectives.atakhan == null })
        assertEquals(8, match.participants.first().kills)
        assertEquals(
            8,
            match.teams
                .first()
                .objectives.tower.killCount,
        )
    }

    @Test
    fun `distinguishes null and zero atakhan from absent atakhan`() {
        val nullObjective = modifiedFixture { it.putNull("atakhan") }
        val zeroObjective = modifiedFixture { it.putObject("atakhan").put("first", false).put("kills", 0) }

        assertNull(
            nullObjective.teams
                .first()
                .objectives.atakhan,
        )
        assertEquals(
            0,
            zeroObjective.teams
                .first()
                .objectives.atakhan
                ?.killCount,
        )
        assertFalse(
            assertNotNull(
                zeroObjective.teams
                    .first()
                    .objectives.atakhan,
            ).wasFirst,
        )
    }

    @Test
    fun `rejects malformed atakhan and missing required objective data`() {
        assertFails { modifiedFixture { it.put("atakhan", "invalid") } }
        assertFails { modifiedFixture { it.putObject("atakhan") } }
        assertFails { modifiedFixture { it.putObject("atakhan").put("first", true) } }
        assertFails { modifiedFixture { it.remove("tower") } }
    }

    @Test
    fun `ignores future unused objective while retaining known objective values`() {
        val match = modifiedFixture { it.putObject("futureObjective").put("first", true).put("kills", 7) }

        assertEquals(
            1,
            match.teams
                .first()
                .objectives.atakhan
                ?.killCount,
        )
        assertEquals(
            8,
            match.teams
                .first()
                .objectives.tower.killCount,
        )
    }

    @Test
    fun `participant statistics do not depend on optional atakhan`() {
        val oldMatch = RiotMatchMapper.toMatch(loadFixture())
        val newMatch =
            RiotMatchMapper.toMatch(
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build().readValue(
                    requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail-without-atakhan.json")).readText(),
                    RiotMatchResponseDto::class.java,
                ),
            )
        val calculator = PlayerMatchStatisticsCalculator()

        assertEquals(
            calculator.calculate("test-puuid-blue-top", listOf(oldMatch)),
            calculator.calculate("test-puuid-blue-top", listOf(newMatch)),
        )
        assertEquals(oldMatch.participants, newMatch.participants)
        assertNull(
            MatchResponse
                .from(newMatch)
                .teams
                .first()
                .objectives.atakhan,
        )
        assertEquals(
            1,
            MatchResponse
                .from(oldMatch)
                .teams
                .first()
                .objectives.atakhan
                ?.killCount,
        )
    }

    @Test
    fun `maps Match metadata and game information into normalized Match`() {
        val match = RiotMatchMapper.toMatch(loadFixture())

        assertEquals("KR_1234567890", match.matchId)
        assertEquals(420, match.queueId)
        assertEquals("CLASSIC", match.gameMode)
        assertEquals("16.17.810.4348", match.gameVersion)
        assertEquals(11, match.mapId)
        assertEquals("KR", match.platformId)
        assertEquals(Instant.ofEpochMilli(1_760_000_000_000), match.startedAt)
        assertEquals(Instant.ofEpochMilli(1_760_001_800_000), match.endedAt)
        assertEquals(Duration.ofSeconds(1_800), match.duration)
    }

    @Test
    fun `maps participant data without Riot DTO slot or perk nesting`() {
        val participant = RiotMatchMapper.toMatch(loadFixture()).participants.first()

        assertEquals(1, participant.participantId)
        assertEquals("test-puuid-blue-top", participant.puuid)
        assertEquals("BlueTop", participant.riotId.gameName)
        assertEquals("TEST1", participant.riotId.tagLine)
        assertEquals(100, participant.teamId)
        assertTrue(participant.won)
        assertEquals("TOP", participant.position)
        assertEquals(266, participant.champion.id)
        assertEquals("Aatrox", participant.champion.name)
        assertEquals(18, participant.champion.level)
        assertEquals(8, participant.kills)
        assertEquals(2, participant.deaths)
        assertEquals(5, participant.assists)
        assertEquals(180, participant.laneMinionKills)
        assertEquals(12, participant.neutralMinionKills)
        assertEquals(12_345, participant.goldEarned)
        assertEquals(24_680, participant.championDamageDealt)
        assertEquals(17_890, participant.damageTaken)
        assertEquals(28, participant.vision.score)
        assertEquals(9, participant.vision.wardsPlaced)
        assertEquals(3, participant.vision.wardsKilled)
        assertEquals(2, participant.turretKills)
        assertEquals(listOf(3_073, 3_065, 6_333, 3_053, 1_031, 0, 3_364), participant.itemIdsBySlot)
        assertEquals(listOf(4, 12), participant.summonerSpellIds)
        assertEquals(5_008, participant.perks.offenseStatPerkId)
        assertEquals(5_010, participant.perks.flexStatPerkId)
        assertEquals(5_011, participant.perks.defenseStatPerkId)
        assertEquals(
            8_010,
            participant.perks.runePaths
                .single()
                .styleId,
        )
        assertEquals(
            listOf(8_005),
            participant.perks.runePaths
                .single()
                .selectedRuneIds,
        )
        assertEquals(4.5, participant.reportedChallenges?.kda)
        assertEquals(0.65, participant.reportedChallenges?.killParticipation)
        assertEquals(822.67, participant.reportedChallenges?.damagePerMinute)
        assertEquals(0.31, participant.reportedChallenges?.teamDamagePercentage)
        assertNull(RiotMatchMapper.toMatch(loadFixture()).participants[1].reportedChallenges)
    }

    @Test
    fun `maps team objectives and preserves a ban sentinel value`() {
        val teams = RiotMatchMapper.toMatch(loadFixture()).teams
        val winningTeam = teams.first()
        val losingTeam = teams.last()

        assertEquals(100, winningTeam.teamId)
        assertTrue(winningTeam.won)
        assertEquals(listOf(238), winningTeam.bannedChampionIds)
        assertTrue(assertNotNull(winningTeam.objectives.atakhan).wasFirst)
        assertEquals(1, winningTeam.objectives.atakhan?.killCount)
        assertEquals(20, winningTeam.objectives.champion.killCount)
        assertEquals(8, winningTeam.objectives.tower.killCount)
        assertFalse(losingTeam.won)
        assertEquals(listOf(-1), losingTeam.bannedChampionIds)
    }

    @Test
    fun `keeps Riot transport nesting out of the internal Match type`() {
        val propertyNames = Match::class.memberProperties.map { it.name }
        val propertyTypeNames = Match::class.memberProperties.map { it.returnType.toString() }

        assertFalse("metadata" in propertyNames)
        assertFalse("info" in propertyNames)
        assertTrue(propertyTypeNames.none { "infrastructure.riot" in it })
    }

    private fun loadFixture(): RiotMatchResponseDto =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build().readValue(
            requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail.json")).readText(),
            RiotMatchResponseDto::class.java,
        )

    private fun modifiedFixture(change: (ObjectNode) -> Unit): Match {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val root = mapper.readTree(requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail.json")).readText())
        change(
            root
                .path("info")
                .path("teams")
                .path(0)
                .path("objectives") as ObjectNode,
        )
        return RiotMatchMapper.toMatch(mapper.readValue(mapper.writeValueAsString(root), RiotMatchResponseDto::class.java))
    }
}
