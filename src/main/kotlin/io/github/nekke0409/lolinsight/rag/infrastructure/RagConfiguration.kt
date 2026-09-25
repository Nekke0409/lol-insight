package io.github.nekke0409.lolinsight.rag.infrastructure

import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.OpenAiProperties
import io.github.nekke0409.lolinsight.rag.application.EmbeddingGateway
import io.github.nekke0409.lolinsight.rag.application.PatchNoteChunker
import io.github.nekke0409.lolinsight.rag.application.PatchNoteDocumentStore
import io.github.nekke0409.lolinsight.rag.application.PatchNoteIndexingService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteParser
import io.github.nekke0409.lolinsight.rag.application.PatchNoteRetrievalService
import io.github.nekke0409.lolinsight.rag.application.RagPersistenceConfigurationException
import io.github.nekke0409.lolinsight.rag.infrastructure.html.DeterministicPatchNoteChunker
import io.github.nekke0409.lolinsight.rag.infrastructure.html.JsoupPatchNoteParser
import io.github.nekke0409.lolinsight.rag.infrastructure.openai.OpenAiPatchNoteEmbeddingGateway
import io.github.nekke0409.lolinsight.rag.persistence.PgvectorPatchNoteDocumentStore
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

@ConfigurationProperties("rag")
data class RagProperties(
    val enabled: Boolean = false,
    val embedding: RagEmbeddingProperties = RagEmbeddingProperties(),
    val chunking: RagChunkingProperties = RagChunkingProperties(),
    val retrieval: RagRetrievalProperties = RagRetrievalProperties(),
    val answer: RagAnswerProperties = RagAnswerProperties(),
)

data class RagEmbeddingProperties(
    val model: String = "text-embedding-3-small",
    val dimensions: Int = 1536,
    val timeout: Duration = Duration.ofSeconds(30),
    val maxBatchSize: Int = 16,
    val maxInputCharacters: Int = 8000,
    val maxTotalBatchCharacters: Int = 24000,
) {
    init {
        require(model.isNotBlank()) { "rag.embedding.model must not be blank" }
        require(dimensions > 0) { "rag.embedding.dimensions must be positive" }
        require(!timeout.isNegative && !timeout.isZero) { "rag.embedding.timeout must be positive" }
        require(maxBatchSize > 0) { "rag.embedding.max-batch-size must be positive" }
        require(maxInputCharacters > 0) { "rag.embedding.max-input-characters must be positive" }
        require(maxTotalBatchCharacters >= maxInputCharacters) {
            "rag.embedding.max-total-batch-characters must be at least max-input-characters"
        }
    }
}

data class RagChunkingProperties(
    val maxCharacters: Int = 2000,
)

data class RagRetrievalProperties(
    val maxTopK: Int = 10,
    val evidenceMaxCharacters: Int = 600,
)

data class RagAnswerProperties(
    val enabled: Boolean = false,
    val maxQuestionCharacters: Int = 1_000,
    val topK: Int = 5,
    val maxEvidenceCharacters: Int = 5_000,
    val maxStatements: Int = 5,
    val maxOutputTokens: Long = 1_200,
    val retrievalTimeout: Duration = Duration.ofSeconds(30),
    val generationTimeout: Duration = Duration.ofSeconds(30),
    val executionDeadline: Duration = Duration.ofSeconds(60),
    val manualCaptureEnabled: Boolean = false,
    val manualPlanPath: String? = null,
    val manualPlanSha256: String? = null,
    val manualPlanLocale: String? = null,
    val manualEvaluationRunId: String? = null,
    val manualCaptureDirectory: Path = Path.of(".local", "rag", "answer-evaluation"),
) {
    init {
        require(maxQuestionCharacters > 0) { "rag.answer.max-question-characters must be positive" }
        require(topK > 0) { "rag.answer.top-k must be positive" }
        require(maxEvidenceCharacters > 0) { "rag.answer.max-evidence-characters must be positive" }
        require(maxStatements > 0) { "rag.answer.max-statements must be positive" }
        require(maxOutputTokens > 0) { "rag.answer.max-output-tokens must be positive" }
        require(!retrievalTimeout.isZero && !retrievalTimeout.isNegative) { "rag.answer.retrieval-timeout must be positive" }
        require(!generationTimeout.isZero && !generationTimeout.isNegative) { "rag.answer.generation-timeout must be positive" }
        require(!executionDeadline.isZero && !executionDeadline.isNegative) { "rag.answer.execution-deadline must be positive" }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag", name = ["enabled"], havingValue = "true")
class RagConfiguration {
    /** Separate history prevents vector-extension migration from touching the baseline Flyway history. */
    @Bean(name = ["ragMigrationRunner"])
    @DependsOn("flyway")
    fun ragMigrationRunner(dataSource: DataSource): RagMigrationRunner = RagMigrationRunner(dataSource)

    @Bean
    fun patchNoteParser(): PatchNoteParser = JsoupPatchNoteParser()

    @Bean
    fun patchNoteChunker(properties: RagProperties): PatchNoteChunker = DeterministicPatchNoteChunker(properties.chunking.maxCharacters)

    @Bean
    fun embeddingGateway(
        properties: RagProperties,
        openAiProperties: OpenAiProperties,
    ): EmbeddingGateway = OpenAiPatchNoteEmbeddingGateway(properties.embedding, openAiProperties.apiKey)

    @Bean
    @DependsOn("ragMigrationRunner")
    fun patchNoteDocumentStore(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): PatchNoteDocumentStore = PgvectorPatchNoteDocumentStore(jdbcTemplate, transactionManager)

    @Bean
    fun patchNoteIndexingService(
        parser: PatchNoteParser,
        chunker: PatchNoteChunker,
        embeddingGateway: EmbeddingGateway,
        store: PatchNoteDocumentStore,
        clock: Clock,
        properties: RagProperties,
    ): PatchNoteIndexingService =
        PatchNoteIndexingService(
            parser = parser,
            chunker = chunker,
            embeddingGateway = embeddingGateway,
            store = store,
            clock = clock,
            maxBatchSize = properties.embedding.maxBatchSize,
            maxTotalBatchCharacters = properties.embedding.maxTotalBatchCharacters,
        )

    @Bean
    fun patchNoteRetrievalService(
        embeddingGateway: EmbeddingGateway,
        store: PatchNoteDocumentStore,
        properties: RagProperties,
    ): PatchNoteRetrievalService =
        PatchNoteRetrievalService(
            embeddingGateway = embeddingGateway,
            store = store,
            maximumTopK = properties.retrieval.maxTopK,
            maximumEvidenceCharacters = properties.retrieval.evidenceMaxCharacters,
        )
}

/**
 * This deliberately is not a Flyway bean: declaring a second Flyway instance would suppress Spring Boot's baseline
 * Flyway auto-configuration. Its isolated history is therefore migrated after the regular application schema.
 */
class RagMigrationRunner(
    dataSource: DataSource,
) : InitializingBean {
    private val flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/rag-migration")
            .table("rag_flyway_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()

    override fun afterPropertiesSet() {
        try {
            flyway.migrate()
        } catch (exception: FlywayException) {
            throw RagPersistenceConfigurationException(exception)
        }
    }
}
