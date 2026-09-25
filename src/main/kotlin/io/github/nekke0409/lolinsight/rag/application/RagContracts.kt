package io.github.nekke0409.lolinsight.rag.application

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PatchNoteSnapshot(
    val sourceUrl: String,
    val title: String,
    val patchVersion: String,
    val locale: String,
    val html: String,
    val publishedAt: Instant? = null,
    val collectedAt: Instant,
) {
    init {
        require(sourceUrl.isNotBlank()) { "sourceUrl must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(patchVersion.isNotBlank()) { "patchVersion must not be blank" }
        require(locale.isNotBlank()) { "locale must not be blank" }
        require(html.isNotBlank()) { "html must not be blank" }
    }
}

data class ParsedPatchNote(
    val snapshot: PatchNoteSnapshot,
    val sections: List<PatchNoteSection>,
) {
    init {
        require(sections.isNotEmpty()) { "patch note must contain at least one searchable section" }
    }
}

data class PatchNoteSection(
    val headingPath: List<String>,
    val body: String,
) {
    init {
        require(headingPath.isNotEmpty()) { "headingPath must not be empty" }
        require(body.isNotBlank()) { "body must not be blank" }
    }
}

data class PatchNoteChunk(
    val index: Int,
    val headingPath: List<String>,
    val contextPrefix: String,
    val body: String,
) {
    init {
        require(index >= 0) { "index must not be negative" }
        require(headingPath.isNotEmpty()) { "headingPath must not be empty" }
        require(contextPrefix.isNotBlank()) { "contextPrefix must not be blank" }
        require(body.isNotBlank()) { "body must not be blank" }
    }

    fun embeddingInput(): String = "$contextPrefix\n\n$body"
}

data class EmbeddingContract(
    val model: String,
    val dimensions: Int,
) {
    init {
        require(model.isNotBlank()) { "embedding model must not be blank" }
        require(dimensions > 0) { "embedding dimensions must be positive" }
    }
}

data class EmbeddingVector(
    val values: List<Float>,
) {
    init {
        require(values.isNotEmpty()) { "embedding vector must not be empty" }
        require(values.all(Float::isFinite)) { "embedding vector must contain finite values" }
        require(values.any { it != 0.0f }) { "embedding vector must not be a zero vector" }
    }
}

data class EmbeddingBatch(
    val contract: EmbeddingContract,
    val vectors: List<EmbeddingVector>,
    val inputTokens: Long? = null,
) {
    init {
        require(inputTokens == null || inputTokens >= 0) { "inputTokens must not be negative" }
    }
}

interface EmbeddingGateway {
    val contract: EmbeddingContract

    fun embed(inputs: List<String>): EmbeddingBatch

    /** A caller may bound a query embedding without changing the configured client default. */
    fun embed(
        inputs: List<String>,
        timeout: Duration,
    ): EmbeddingBatch = embed(inputs)
}

interface PatchNoteParser {
    val version: String

    fun parse(snapshot: PatchNoteSnapshot): ParsedPatchNote
}

interface PatchNoteChunker {
    val version: String

    fun chunk(document: ParsedPatchNote): List<PatchNoteChunk>
}

data class IndexedPatchNote(
    val id: UUID,
    val revisionFingerprint: String,
    val snapshot: PatchNoteSnapshot,
    val contentHash: String,
    val parserVersion: String,
    val chunkerVersion: String,
    val embeddingContract: EmbeddingContract,
    val chunks: List<IndexedPatchNoteChunk>,
    val createdAt: Instant,
)

data class IndexedPatchNoteChunk(
    val id: UUID,
    val chunk: PatchNoteChunk,
    val embeddingInputHash: String,
    val embedding: EmbeddingVector,
)

data class StoredPatchNoteRevision(
    val id: UUID,
    val revisionFingerprint: String,
    val isActive: Boolean,
)

data class PatchNoteSearchRow(
    val chunkId: UUID,
    val documentId: UUID,
    val title: String,
    val sourceUrl: String,
    val patchVersion: String,
    val locale: String,
    val revisionFingerprint: String,
    val headingPath: List<String>,
    val body: String,
    val cosineDistance: Double,
)

interface PatchNoteDocumentStore {
    fun findRevision(revisionFingerprint: String): StoredPatchNoteRevision?

    fun activateOrStore(indexed: IndexedPatchNote): StoredPatchNoteRevision

    fun findActiveEmbeddingContracts(
        patchVersion: String,
        locale: String,
    ): Set<EmbeddingContract>

    fun findActiveEmbeddingContracts(
        patchVersion: String,
        locale: String,
        timeout: Duration,
    ): Set<EmbeddingContract> = findActiveEmbeddingContracts(patchVersion, locale)

    fun search(
        patchVersion: String,
        locale: String,
        contract: EmbeddingContract,
        queryEmbedding: EmbeddingVector,
        topK: Int,
    ): List<PatchNoteSearchRow>

    fun search(
        patchVersion: String,
        locale: String,
        contract: EmbeddingContract,
        queryEmbedding: EmbeddingVector,
        topK: Int,
        timeout: Duration,
    ): List<PatchNoteSearchRow> = search(patchVersion, locale, contract, queryEmbedding, topK)
}

sealed class RagException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class PatchNoteParsingException(
    message: String,
) : RagException(message)

class PatchNoteChunkingException(
    message: String,
) : RagException(message)

class RagEmbeddingConfigurationException : RagException("RAG embedding is not configured")

class RagEmbeddingProviderException(
    cause: Throwable,
) : RagException("RAG embedding provider request failed", cause)

class RagEmbeddingTransportException(
    cause: Throwable,
) : RagException("RAG embedding provider transport request failed", cause)

class RagEmbeddingInvalidResponseException(
    message: String,
    cause: Throwable? = null,
) : RagException(message, cause)

class RagCorpusContractMismatchException(
    message: String,
) : RagException(message)

class RagPersistenceConfigurationException(
    cause: Throwable,
) : RagException("RAG pgvector migration or extension is not configured correctly", cause)

class RagFeatureDisabledException : RagException("RAG patch-note retrieval is not enabled")
