package io.github.nekke0409.lolinsight.rag

import io.github.nekke0409.lolinsight.rag.application.EmbeddingBatch
import io.github.nekke0409.lolinsight.rag.application.EmbeddingContract
import io.github.nekke0409.lolinsight.rag.application.EmbeddingGateway
import io.github.nekke0409.lolinsight.rag.application.PatchNoteChunk
import io.github.nekke0409.lolinsight.rag.application.PatchNoteIndexingOutcome
import io.github.nekke0409.lolinsight.rag.application.PatchNoteIndexingService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteRetrievalService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSearchRequest
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSnapshot
import io.github.nekke0409.lolinsight.rag.infrastructure.RagEmbeddingProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagMigrationRunner
import io.github.nekke0409.lolinsight.rag.infrastructure.html.DeterministicPatchNoteChunker
import io.github.nekke0409.lolinsight.rag.infrastructure.html.JsoupPatchNoteParser
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.OpenAiPatchNoteEmbeddingGateway
import io.github.nekke0409.lolinsight.rag.persistence.PgvectorPatchNoteDocumentStore
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generic opt-in live verification for one locally saved patch-note snapshot and one supplied question plan.
 *
 * It never uses the application's configured datasource, Redis, Riot clients, analysis jobs, automation, or an HTTP
 * endpoint. The isolated Testcontainers database is deliberately exported to a caller-supplied ignored path before its
 * container exits, so a successful run can be restored by first applying the RAG migration and then its data-only dump.
 */
@EnabledIfEnvironmentVariable(named = "RUN_RAG_RETRIEVAL_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@Testcontainers
class PatchNoteRetrievalManualSmokeTest {
    @Test
    fun `indexes one supplied snapshot once then evaluates its supplied questions`() {
        val settings = ManualSmokeSettings.fromEnvironment()
        val snapshot = settings.snapshot()
        verifySnapshotMetadata(snapshot, settings)

        val parser = JsoupPatchNoteParser()
        val chunker = DeterministicPatchNoteChunker(MAXIMUM_CHUNK_CHARACTERS)
        val dryRunChunks = chunker.chunk(parser.parse(snapshot))
        val expectedDocumentBatches = dryRunChunks.embeddingInputs().boundedBatchCount()
        verifyDryRun(dryRunChunks, expectedDocumentBatches)
        printDryRun(dryRunChunks, expectedDocumentBatches, parser.version, chunker.version)
        if (System.getenv("RAG_SMOKE_DRY_RUN") == "true") {
            println("RAG dry run completed without database migration or embedding requests")
            return
        }

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            ).also { it.setDriverClassName("org.postgresql.Driver") }
        RagMigrationRunner(dataSource).afterPropertiesSet()
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        verifyIsolatedTarget(jdbc)
        val store = PgvectorPatchNoteDocumentStore(jdbc, DataSourceTransactionManager(dataSource))
        val delegate =
            OpenAiPatchNoteEmbeddingGateway(
                RagEmbeddingProperties(
                    model = EMBEDDING_MODEL,
                    dimensions = EMBEDDING_DIMENSIONS,
                    timeout = Duration.ofSeconds(30),
                    maxBatchSize = MAXIMUM_BATCH_SIZE,
                    maxInputCharacters = MAXIMUM_INPUT_CHARACTERS,
                    maxTotalBatchCharacters = MAXIMUM_BATCH_CHARACTERS,
                ),
                System.getenv("OPENAI_API_KEY"),
            )
        val gateway = CappedObservingEmbeddingGateway(delegate)
        val indexing =
            PatchNoteIndexingService(
                parser = parser,
                chunker = chunker,
                embeddingGateway = gateway,
                store = store,
                clock = Clock.systemUTC(),
                maxBatchSize = MAXIMUM_BATCH_SIZE,
                maxTotalBatchCharacters = MAXIMUM_BATCH_CHARACTERS,
            )
        val retrieval =
            PatchNoteRetrievalService(
                embeddingGateway = gateway,
                store = store,
                maximumTopK = TOP_K,
                maximumEvidenceCharacters = EVIDENCE_MAXIMUM_CHARACTERS,
            )

        gateway.phase = EmbeddingPhase.DOCUMENT
        val firstIndex = indexing.index(snapshot)
        assertEquals(PatchNoteIndexingOutcome.INDEXED, firstIndex.outcome)
        assertEquals(dryRunChunks.size, firstIndex.chunkCount)
        assertEquals(expectedDocumentBatches, gateway.documentAttempts)

        val attemptsAfterFirstIndex = gateway.documentAttempts
        val repeatedIndex = indexing.index(snapshot)
        assertEquals(PatchNoteIndexingOutcome.ALREADY_INDEXED, repeatedIndex.outcome)
        assertEquals(attemptsAfterFirstIndex, gateway.documentAttempts)

        settings.questions.forEach { question ->
            gateway.phase = EmbeddingPhase.QUERY
            val queryAttemptsBefore = gateway.queryAttempts
            val results =
                retrieval.search(
                    PatchNoteSearchRequest(
                        query = question.query,
                        patchVersion = question.patchVersion,
                        locale = snapshot.locale,
                        topK = TOP_K,
                    ),
                )
            if (question.kind == QuestionKind.UNINDEXED) {
                assertTrue(results.isEmpty())
                assertEquals(queryAttemptsBefore, gateway.queryAttempts)
            }
            printQuestionAssessment(question, results, jdbc)
        }
        assertEquals(QUERY_EMBEDDING_ATTEMPT_CAP, gateway.queryAttempts)
        gateway.printSummary()
        exportRagData(settings.exportPath)
    }

    private fun verifySnapshotMetadata(
        snapshot: PatchNoteSnapshot,
        settings: ManualSmokeSettings,
    ) {
        assertEquals(OFFICIAL_SOURCE_URL, snapshot.sourceUrl)
        assertEquals("ko-KR", snapshot.locale)
        assertEquals(settings.snapshotHash, sha256(Files.readAllBytes(settings.snapshotPath)))
        val page = Jsoup.parse(snapshot.html, snapshot.sourceUrl)
        assertEquals(snapshot.title, page.title())
        assertEquals(snapshot.publishedAt, page.selectFirst("time[datetime]")?.attr("datetime")?.let(Instant::parse))
    }

    private fun verifyDryRun(
        chunks: List<PatchNoteChunk>,
        expectedDocumentBatches: Int,
    ) {
        assertTrue(chunks.size <= DOCUMENT_CHUNK_CAP)
        assertTrue(chunks.embeddingInputs().sumOf(String::length) <= DOCUMENT_CHARACTER_CAP)
        assertTrue(chunks.embeddingInputs().all { it.length <= MAXIMUM_INPUT_CHARACTERS })
        assertTrue(expectedDocumentBatches <= DOCUMENT_EMBEDDING_ATTEMPT_CAP)
        assertTrue(chunks.all { it.headingPath.isNotEmpty() && it.body.isNotBlank() })
    }

    private fun printDryRun(
        chunks: List<PatchNoteChunk>,
        expectedDocumentBatches: Int,
        parserVersion: String,
        chunkerVersion: String,
    ) {
        println(
            "RAG dry run: sections=${chunks.map { it.headingPath }.distinct().size}, chunks=${chunks.size}, " +
                "embeddingInputChars=${chunks.embeddingInputs().sumOf(String::length)}, " +
                "maxEmbeddingInputChars=${chunks.embeddingInputs().maxOf(
                    String::length,
                )}, expectedDocumentRequests=$expectedDocumentBatches, " +
                "parser=$parserVersion, chunker=$chunkerVersion, maxRetries=0",
        )
        chunks.filter { chunk -> chunk.headingPath.last() in setOf("초가스", "Q - 파열", "룰루", "R - 급성장", "피들스틱", "Q - 공포") }.forEach { chunk ->
            println("RAG dry run heading: ${chunk.headingPath.joinToString(" > ")}; bodyChars=${chunk.body.length}")
        }
    }

    private fun verifyIsolatedTarget(jdbc: NamedParameterJdbcTemplate) {
        val version = requireNotNull(jdbc.jdbcTemplate.queryForObject("SHOW server_version", String::class.java))
        val database = requireNotNull(jdbc.jdbcTemplate.queryForObject("SELECT current_database()", String::class.java))
        assertTrue(version.startsWith("17."))
        assertEquals(postgres.databaseName, database)
        println("RAG isolated target: image=$POSTGRES_IMAGE, database=$database, serverVersion=$version")
    }

    private fun printQuestionAssessment(
        question: ManualSmokeQuestion,
        results: List<io.github.nekke0409.lolinsight.rag.application.PatchNoteSearchResult>,
        jdbc: NamedParameterJdbcTemplate,
    ) {
        val expectedRank =
            if (question.kind == QuestionKind.GROUNDED) {
                results.indexOfFirst { result -> result.headingPath.startsWith(question.expectedHeadingPath) }.takeIf { it >= 0 }?.plus(1)
            } else {
                null
            }
        println(
            "RAG question: id=${question.id}, kind=${question.kind}, firstExpectedRank=${expectedRank ?: "none"}, " +
                "hitAt1=${expectedRank == 1}, hitAt3=${expectedRank?.let { it <= 3 } ?: false}, " +
                "hitAt5=${expectedRank?.let { it <= 5 } ?: false}, requiredFacts=${question.requiredFacts}, " +
                "location=${question.locationHint}",
        )
        results.forEachIndexed { index, result ->
            val storedBodyLength =
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT body FROM rag_patch_note_chunk WHERE id = :id",
                        mapOf("id" to result.chunkId),
                        String::class.java,
                    ),
                ).length
            println(
                "RAG result: question=${question.id}, rank=${index + 1}, similarity=${result.cosineSimilarity}, " +
                    "heading=${result.headingPath.joinToString(" > ")}, storedBodyChars=$storedBodyLength, " +
                    "evidenceChars=${result.evidenceText.length}, evidenceTruncated=${storedBodyLength > result.evidenceText.length}, " +
                    "evidence=${result.evidenceText.replace('\n', ' ')}",
            )
        }
    }

    private fun exportRagData(path: Path) {
        Files.createDirectories(path.parent)
        val dump =
            postgres.execInContainer(
                "pg_dump",
                "-U",
                postgres.username,
                "-d",
                postgres.databaseName,
                "--data-only",
                "--table=rag_patch_note_document_revision",
                "--table=rag_patch_note_chunk",
            )
        check(dump.exitCode == 0) { "isolated RAG export failed" }
        Files.writeString(path, dump.stdout)
        println("RAG isolated data export: path=$path, bytes=${Files.size(path)}")
    }

    private fun List<PatchNoteChunk>.embeddingInputs(): List<String> = map(PatchNoteChunk::embeddingInput)

    private fun List<String>.boundedBatchCount(): Int {
        var batches = 0
        var currentCount = 0
        var currentCharacters = 0
        for (input in this) {
            if (currentCount > 0 && (currentCount == MAXIMUM_BATCH_SIZE || currentCharacters + input.length > MAXIMUM_BATCH_CHARACTERS)) {
                batches += 1
                currentCount = 0
                currentCharacters = 0
            }
            currentCount += 1
            currentCharacters += input.length
        }
        return if (currentCount == 0) batches else batches + 1
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class CappedObservingEmbeddingGateway(
        private val delegate: EmbeddingGateway,
    ) : EmbeddingGateway {
        override val contract: EmbeddingContract = delegate.contract
        lateinit var phase: EmbeddingPhase
        private val calls = mutableListOf<EmbeddingCallObservation>()

        val documentAttempts: Int get() = calls.count { it.phase == EmbeddingPhase.DOCUMENT }
        val queryAttempts: Int get() = calls.count { it.phase == EmbeddingPhase.QUERY }

        override fun embed(inputs: List<String>): EmbeddingBatch {
            check(inputs.size <= MAXIMUM_BATCH_SIZE)
            check(inputs.sumOf(String::length) <= MAXIMUM_BATCH_CHARACTERS)
            val currentPhase = phase
            val attempts = if (currentPhase == EmbeddingPhase.DOCUMENT) documentAttempts else queryAttempts
            val cap = if (currentPhase == EmbeddingPhase.DOCUMENT) DOCUMENT_EMBEDDING_ATTEMPT_CAP else QUERY_EMBEDDING_ATTEMPT_CAP
            check(attempts < cap) { "$currentPhase embedding attempt cap reached" }
            val startedAt = System.nanoTime()
            return try {
                delegate.embed(inputs).also { batch ->
                    calls +=
                        EmbeddingCallObservation(
                            phase = currentPhase,
                            inputCount = inputs.size,
                            inputCharacters = inputs.sumOf(String::length),
                            promptTokens = batch.inputTokens,
                            latency = Duration.ofNanos(System.nanoTime() - startedAt),
                            outcome = "success",
                        )
                }
            } catch (exception: RuntimeException) {
                calls +=
                    EmbeddingCallObservation(
                        phase = currentPhase,
                        inputCount = inputs.size,
                        inputCharacters = inputs.sumOf(String::length),
                        promptTokens = null,
                        latency = Duration.ofNanos(System.nanoTime() - startedAt),
                        outcome = exception::class.simpleName ?: "failure",
                    )
                throw exception
            }
        }

        fun printSummary() {
            calls.forEachIndexed { index, call ->
                println(
                    "RAG embedding call: number=${index + 1}, phase=${call.phase}, " +
                        "model=${contract.model}, dimensions=${contract.dimensions}, " +
                        "inputs=${call.inputCount}, inputChars=${call.inputCharacters}, " +
                        "promptTokens=${call.promptTokens ?: "unavailable"}, " +
                        "latency=${call.latency}, outcome=${call.outcome}",
                )
            }
        }
    }

    private data class EmbeddingCallObservation(
        val phase: EmbeddingPhase,
        val inputCount: Int,
        val inputCharacters: Int,
        val promptTokens: Long?,
        val latency: Duration,
        val outcome: String,
    )

    private enum class EmbeddingPhase {
        DOCUMENT,
        QUERY,
    }

    private enum class QuestionKind {
        GROUNDED,
        INSUFFICIENT,
        UNINDEXED,
    }

    private data class ManualSmokeQuestion(
        val id: String,
        val kind: QuestionKind,
        val query: String,
        val patchVersion: String,
        val expectedHeadingPath: List<String>,
        val requiredFacts: String,
        val locationHint: String,
    )

    private data class ManualSmokeSettings(
        val snapshotPath: Path,
        val metadata: Properties,
        val questions: List<ManualSmokeQuestion>,
        val exportPath: Path,
    ) {
        val snapshotHash: String get() = metadata.required("snapshotSha256")

        fun snapshot(): PatchNoteSnapshot =
            PatchNoteSnapshot(
                sourceUrl = metadata.required("sourceUrl"),
                title = metadata.required("title"),
                patchVersion = metadata.required("patchVersion"),
                locale = metadata.required("locale"),
                html = Files.readString(snapshotPath),
                publishedAt = Instant.parse(metadata.required("publishedAt")),
                collectedAt = Instant.parse(metadata.required("collectedAt")),
            )

        companion object {
            fun fromEnvironment(): ManualSmokeSettings {
                val snapshotPath = Path.of(requiredEnvironment("RAG_SMOKE_SNAPSHOT_PATH"))
                val metadata = loadProperties(Path.of(requiredEnvironment("RAG_SMOKE_METADATA_PATH")))
                val questionProperties = loadProperties(Path.of(requiredEnvironment("RAG_SMOKE_QUESTIONS_PATH")))
                val questions =
                    (1..questionProperties.required("question.count").toInt()).map { index ->
                        val prefix = "question.$index."
                        ManualSmokeQuestion(
                            id = questionProperties.required(prefix + "id"),
                            kind = QuestionKind.valueOf(questionProperties.required(prefix + "kind")),
                            query = questionProperties.required(prefix + "query"),
                            patchVersion = questionProperties.required(prefix + "patchVersion"),
                            expectedHeadingPath = questionProperties.required(prefix + "expectedHeadingPath").split(" > "),
                            requiredFacts = questionProperties.required(prefix + "requiredFacts"),
                            locationHint = questionProperties.required(prefix + "locationHint"),
                        )
                    }
                require(questions.size == 6) { "manual smoke requires exactly six locked questions" }
                require(questions.count { it.kind == QuestionKind.GROUNDED } == 4) { "manual smoke requires four grounded questions" }
                require(
                    questions.count { it.kind == QuestionKind.INSUFFICIENT } == 1,
                ) { "manual smoke requires one insufficient-data question" }
                require(questions.count { it.kind == QuestionKind.UNINDEXED } == 1) { "manual smoke requires one unindexed question" }
                return ManualSmokeSettings(
                    snapshotPath = snapshotPath,
                    metadata = metadata,
                    questions = questions,
                    exportPath = Path.of(requiredEnvironment("RAG_SMOKE_EXPORT_PATH")),
                )
            }

            private fun loadProperties(path: Path): Properties =
                Properties().also { properties ->
                    Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
                }

            private fun requiredEnvironment(name: String): String =
                System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name must be set for manual RAG smoke")
        }
    }

    private companion object {
        const val OFFICIAL_SOURCE_URL = "https://www.leagueoflegends.com/ko-kr/news/game-updates/patch-25-10-notes/"
        const val POSTGRES_IMAGE = "pgvector/pgvector:0.8.0-pg17"
        const val EMBEDDING_MODEL = "text-embedding-3-small"
        const val EMBEDDING_DIMENSIONS = 1536
        const val MAXIMUM_BATCH_SIZE = 16
        const val MAXIMUM_INPUT_CHARACTERS = 8_000
        const val MAXIMUM_BATCH_CHARACTERS = 24_000
        const val MAXIMUM_CHUNK_CHARACTERS = 2_000
        const val DOCUMENT_CHUNK_CAP = 128
        const val DOCUMENT_CHARACTER_CAP = 120_000
        const val DOCUMENT_EMBEDDING_ATTEMPT_CAP = 16
        const val QUERY_EMBEDDING_ATTEMPT_CAP = 5
        const val TOP_K = 5
        const val EVIDENCE_MAXIMUM_CHARACTERS = 600

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
    }
}

private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && take(prefix.size) == prefix

private fun Properties.required(key: String): String =
    getProperty(key)?.trim()?.takeIf(String::isNotBlank) ?: error("$key must be set in the manual RAG smoke properties file")
