package io.github.nekke0409.lolinsight.rag.application

import java.time.Duration

class PatchNoteRetrievalService(
    private val embeddingGateway: EmbeddingGateway,
    private val store: PatchNoteDocumentStore,
    private val maximumTopK: Int,
    private val maximumEvidenceCharacters: Int,
) {
    init {
        require(maximumTopK > 0) { "maximumTopK must be positive" }
        require(maximumEvidenceCharacters > 0) { "maximumEvidenceCharacters must be positive" }
    }

    fun search(request: PatchNoteSearchRequest): List<PatchNoteSearchResult> = searchWithExecution(request, null).results

    fun searchWithExecution(
        request: PatchNoteSearchRequest,
        timeout: Duration?,
        allowQueryEmbedding: Boolean = true,
    ): PatchNoteRetrievalExecution {
        request.validate(maximumTopK)
        val deadline = timeout?.let(PatchNoteRetrievalDeadline::after)
        val contracts =
            deadline?.withRemaining { remaining ->
                store.findActiveEmbeddingContracts(request.patchVersion, request.locale, remaining)
            } ?: store.findActiveEmbeddingContracts(request.patchVersion, request.locale)
        if (contracts.isEmpty()) {
            return PatchNoteRetrievalExecution(
                emptyList(),
                queryEmbeddingAttempted = false,
                queryEmbeddingInputTokens = null,
                embeddingLatency = null,
            )
        }
        if (contracts != setOf(embeddingGateway.contract)) {
            throw RagCorpusContractMismatchException(
                "active patch-note corpus must be reindexed for embedding contract ${embeddingGateway.contract.model}/${embeddingGateway.contract.dimensions}",
            )
        }
        if (!allowQueryEmbedding) {
            throw PatchNoteRetrievalExecutionBudgetExceededException()
        }

        val embeddingStartedAt = System.nanoTime()
        val response =
            deadline?.withRemaining { remaining -> embeddingGateway.embed(listOf(request.query), remaining) }
                ?: embeddingGateway.embed(listOf(request.query))
        val embeddingLatency = Duration.ofNanos(System.nanoTime() - embeddingStartedAt)
        if (response.contract != embeddingGateway.contract || response.vectors.size != 1) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned an invalid query embedding response")
        }
        val queryEmbedding = response.vectors.single()
        if (queryEmbedding.values.size != response.contract.dimensions) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned an unexpected query vector dimension")
        }

        val rows =
            deadline?.withRemaining { remaining ->
                store.search(
                    patchVersion = request.patchVersion,
                    locale = request.locale,
                    contract = embeddingGateway.contract,
                    queryEmbedding = queryEmbedding,
                    topK = request.topK,
                    timeout = remaining,
                )
            } ?: store
                .search(
                    patchVersion = request.patchVersion,
                    locale = request.locale,
                    contract = embeddingGateway.contract,
                    queryEmbedding = queryEmbedding,
                    topK = request.topK,
                )
        return PatchNoteRetrievalExecution(
            results =
                rows.map { row ->
                    PatchNoteSearchResult(
                        chunkId = row.chunkId,
                        documentId = row.documentId,
                        title = row.title,
                        sourceUrl = row.sourceUrl,
                        patchVersion = row.patchVersion,
                        locale = row.locale,
                        revisionFingerprint = row.revisionFingerprint,
                        headingPath = row.headingPath,
                        evidenceText = row.body.truncate(maximumEvidenceCharacters),
                        cosineDistance = row.cosineDistance,
                    )
                },
            queryEmbeddingAttempted = true,
            queryEmbeddingInputTokens = response.inputTokens,
            embeddingLatency = embeddingLatency,
        )
    }
}

data class PatchNoteRetrievalExecution(
    val results: List<PatchNoteSearchResult>,
    val queryEmbeddingAttempted: Boolean,
    val queryEmbeddingInputTokens: Long?,
    val embeddingLatency: Duration?,
)

private class PatchNoteRetrievalDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun <T> withRemaining(call: (Duration) -> T): T {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw PatchNoteRetrievalDeadlineExceededException()
        return call(Duration.ofNanos(remaining))
    }

    companion object {
        fun after(timeout: Duration): PatchNoteRetrievalDeadline =
            PatchNoteRetrievalDeadline(Math.addExact(System.nanoTime(), timeout.toNanos()))
    }
}

class PatchNoteRetrievalDeadlineExceededException : RagException("RAG retrieval deadline exceeded")

class PatchNoteRetrievalExecutionBudgetExceededException : RagException("RAG query embedding execution budget is exhausted")

data class PatchNoteSearchRequest(
    val query: String,
    val patchVersion: String,
    val locale: String,
    val topK: Int,
) {
    internal fun validate(maximumTopK: Int) {
        require(query.isNotBlank()) { "query must not be blank" }
        require(patchVersion.isNotBlank()) { "patchVersion must not be blank" }
        require(locale.isNotBlank()) { "locale must not be blank" }
        require(topK in 1..maximumTopK) { "topK must be between 1 and $maximumTopK" }
    }
}

data class PatchNoteSearchResult(
    val chunkId: java.util.UUID,
    val documentId: java.util.UUID,
    val title: String,
    val sourceUrl: String,
    val patchVersion: String,
    val locale: String,
    val revisionFingerprint: String,
    val headingPath: List<String>,
    val evidenceText: String,
    val cosineDistance: Double,
) {
    /** pgvector cosine distance: lower values are nearer in the selected corpus; it is not an answer-confidence score. */
    val cosineSimilarity: Double = 1.0 - cosineDistance
}

private fun String.truncate(maximumLength: Int): String = if (length <= maximumLength) this else take(maximumLength - 1).trimEnd() + "…"
