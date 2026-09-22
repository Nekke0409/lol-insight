package io.github.nekke0409.lolinsight.rag.application

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

class PatchNoteIndexingService(
    private val parser: PatchNoteParser,
    private val chunker: PatchNoteChunker,
    private val embeddingGateway: EmbeddingGateway,
    private val store: PatchNoteDocumentStore,
    private val clock: Clock,
    private val maxBatchSize: Int,
    private val maxTotalBatchCharacters: Int,
) {
    init {
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        require(maxTotalBatchCharacters > 0) { "maxTotalBatchCharacters must be positive" }
    }

    fun index(snapshot: PatchNoteSnapshot): PatchNoteIndexingResult {
        val contentHash = sha256(snapshot.html)
        val revisionFingerprint =
            revisionFingerprint(
                snapshot = snapshot,
                contentHash = contentHash,
                parserVersion = parser.version,
                chunkerVersion = chunker.version,
                embeddingContract = embeddingGateway.contract,
            )

        store.findRevision(revisionFingerprint)?.let { existing ->
            val activated = store.activateOrStore(existing.asIndexedPlaceholder(snapshot, contentHash))
            return PatchNoteIndexingResult(
                documentId = activated.id,
                revisionFingerprint = activated.revisionFingerprint,
                outcome = if (existing.isActive) PatchNoteIndexingOutcome.ALREADY_INDEXED else PatchNoteIndexingOutcome.REACTIVATED,
                chunkCount = 0,
            )
        }

        val chunks = chunker.chunk(parser.parse(snapshot))
        if (chunks.isEmpty()) {
            throw PatchNoteChunkingException("patch note did not produce searchable chunks")
        }

        val embeddings = embedChunks(chunks)
        val indexed =
            IndexedPatchNote(
                id = UUID.randomUUID(),
                revisionFingerprint = revisionFingerprint,
                snapshot = snapshot,
                contentHash = contentHash,
                parserVersion = parser.version,
                chunkerVersion = chunker.version,
                embeddingContract = embeddingGateway.contract,
                chunks =
                    chunks.zip(embeddings).map { (chunk, embedding) ->
                        IndexedPatchNoteChunk(
                            id = UUID.randomUUID(),
                            chunk = chunk,
                            embeddingInputHash = sha256(chunk.embeddingInput()),
                            embedding = embedding,
                        )
                    },
                createdAt = clock.instant(),
            )
        val stored = store.activateOrStore(indexed)

        return PatchNoteIndexingResult(
            documentId = stored.id,
            revisionFingerprint = stored.revisionFingerprint,
            outcome = PatchNoteIndexingOutcome.INDEXED,
            chunkCount = indexed.chunks.size,
        )
    }

    private fun embedChunks(chunks: List<PatchNoteChunk>): List<EmbeddingVector> {
        val inputs = chunks.map(PatchNoteChunk::embeddingInput)
        val batches = inputs.boundedBatches(maxBatchSize, maxTotalBatchCharacters)
        val vectors =
            batches.flatMap { batch ->
                val response = embeddingGateway.embed(batch)
                validateEmbeddingResponse(batch, response)
                response.vectors
            }
        check(vectors.size == inputs.size) { "embedding output count must match chunk count" }
        return vectors
    }

    private fun validateEmbeddingResponse(
        inputs: List<String>,
        response: EmbeddingBatch,
    ) {
        if (response.contract != embeddingGateway.contract) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned a different model or dimension")
        }
        if (response.vectors.size != inputs.size) {
            throw RagEmbeddingInvalidResponseException("embedding provider response count does not match input count")
        }
        if (response.vectors.any { it.values.size != response.contract.dimensions }) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned an unexpected vector dimension")
        }
    }

    private fun List<String>.boundedBatches(
        maximumBatchSize: Int,
        maximumTotalCharacters: Int,
    ): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentCharacters = 0

        for (input in this) {
            if (input.length > maximumTotalCharacters) {
                throw RagEmbeddingInvalidResponseException("embedding input exceeds the configured batch character limit")
            }
            if (current.isNotEmpty() && (current.size == maximumBatchSize || currentCharacters + input.length > maximumTotalCharacters)) {
                batches += current
                current = mutableListOf()
                currentCharacters = 0
            }
            current += input
            currentCharacters += input.length
        }
        if (current.isNotEmpty()) {
            batches += current
        }
        return batches
    }

    private fun StoredPatchNoteRevision.asIndexedPlaceholder(
        snapshot: PatchNoteSnapshot,
        contentHash: String,
    ): IndexedPatchNote =
        IndexedPatchNote(
            id = id,
            revisionFingerprint = revisionFingerprint,
            snapshot = snapshot,
            contentHash = contentHash,
            parserVersion = parser.version,
            chunkerVersion = chunker.version,
            embeddingContract = embeddingGateway.contract,
            chunks = emptyList(),
            createdAt = clock.instant(),
        )

    private fun revisionFingerprint(
        snapshot: PatchNoteSnapshot,
        contentHash: String,
        parserVersion: String,
        chunkerVersion: String,
        embeddingContract: EmbeddingContract,
    ): String =
        sha256(
            listOf(
                snapshot.sourceUrl,
                snapshot.patchVersion,
                snapshot.locale,
                contentHash,
                parserVersion,
                chunkerVersion,
                embeddingContract.model,
                embeddingContract.dimensions.toString(),
            ).joinToString("\u0000"),
        )
}

data class PatchNoteIndexingResult(
    val documentId: UUID,
    val revisionFingerprint: String,
    val outcome: PatchNoteIndexingOutcome,
    val chunkCount: Int,
)

enum class PatchNoteIndexingOutcome {
    INDEXED,
    ALREADY_INDEXED,
    REACTIVATED,
}

internal fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
