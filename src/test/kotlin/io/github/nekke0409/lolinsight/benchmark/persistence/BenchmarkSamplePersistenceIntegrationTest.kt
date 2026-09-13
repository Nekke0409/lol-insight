package io.github.nekke0409.lolinsight.benchmark.persistence

import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSamplePersistenceService
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkSampleSaveResult
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkSample
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BenchmarkSamplePersistenceService::class)
@Testcontainers
class BenchmarkSamplePersistenceIntegrationTest {
    @Autowired
    private lateinit var benchmarkSampleJpaRepository: BenchmarkSampleJpaRepository

    @Autowired
    private lateinit var benchmarkSamplePersistenceService: BenchmarkSamplePersistenceService

    @Test
    fun `Flyway migration and JPA mapping save and restore a BenchmarkSample`() {
        val sample = sample()

        assertEquals(BenchmarkSampleSaveResult.INSERTED, benchmarkSamplePersistenceService.saveIfAbsent(sample))

        val restored =
            requireNotNull(
                benchmarkSampleJpaRepository.findByMatchIdAndPuuid(sample.matchId, sample.puuid),
            ).toDomain()

        assertNotNull(restored.id)
        assertEquals(sample, restored.copy(id = null))
        assertEquals("GOLD", restored.tier)
        assertEquals("I", restored.division)
        assertEquals(Instant.parse("2026-09-13T10:15:30Z"), restored.rankCapturedAt)
        assertEquals(Instant.parse("2026-09-13T09:00:00Z"), restored.gameStartTimestamp)
        assertEquals(7.25, restored.kda)
        assertEquals(0.42, restored.damageShare)
    }

    @Test
    fun `database rejects duplicate matchId and puuid`() {
        benchmarkSampleJpaRepository.saveAndFlush(sample().toEntity())

        assertFailsWith<DataIntegrityViolationException> {
            benchmarkSampleJpaRepository.saveAndFlush(sample().toEntity())
        }
    }

    @Test
    fun `database allows the same matchId for a different puuid`() {
        benchmarkSampleJpaRepository.saveAndFlush(sample().toEntity())
        benchmarkSampleJpaRepository.saveAndFlush(sample(puuid = "other-puuid").toEntity())

        assertEquals(2, benchmarkSampleJpaRepository.count())
    }

    @Test
    fun `database allows the same puuid for a different matchId`() {
        benchmarkSampleJpaRepository.saveAndFlush(sample().toEntity())
        benchmarkSampleJpaRepository.saveAndFlush(sample(matchId = "KR_987654321").toEntity())

        assertEquals(2, benchmarkSampleJpaRepository.count())
    }

    @Test
    fun `saveIfAbsent leaves the existing BenchmarkSample unchanged`() {
        val first = sample()
        val duplicate = sample(kills = 99, kda = 99.0)

        assertEquals(BenchmarkSampleSaveResult.INSERTED, benchmarkSamplePersistenceService.saveIfAbsent(first))
        assertEquals(BenchmarkSampleSaveResult.ALREADY_EXISTS, benchmarkSamplePersistenceService.saveIfAbsent(duplicate))

        val saved =
            requireNotNull(
                benchmarkSampleJpaRepository.findByMatchIdAndPuuid(first.matchId, first.puuid),
            ).toDomain()
        assertEquals(first.kills, saved.kills)
        assertEquals(first.kda, saved.kda)
        assertEquals(1, benchmarkSampleJpaRepository.count())
    }

    private fun sample(
        matchId: String = "KR_123456789",
        puuid: String = "sampled-puuid",
        kills: Int = 8,
        kda: Double = 7.25,
    ): BenchmarkSample =
        BenchmarkSample(
            matchId = matchId,
            puuid = puuid,
            region = "KR",
            queueId = 420,
            tier = "GOLD",
            division = "I",
            rankCapturedAt = Instant.parse("2026-09-13T10:15:30Z"),
            championId = 103,
            position = "MIDDLE",
            gameVersion = "16.18.1",
            gameStartTimestamp = Instant.parse("2026-09-13T09:00:00Z"),
            kills = kills,
            deaths = 2,
            assists = 7,
            kda = kda,
            csPerMinute = 8.4,
            goldPerMinute = 451.2,
            damagePerMinute = 732.8,
            visionPerMinute = 1.3,
            killParticipation = 0.64,
            damageShare = 0.42,
            collectedAt = Instant.parse("2026-09-13T10:16:00Z"),
        )

    companion object {
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
