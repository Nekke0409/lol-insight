package io.github.nekke0409.lolinsight.rag.application

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

    fun search(request: PatchNoteSearchRequest): List<PatchNoteSearchResult> {
        request.validate(maximumTopK)
        val contracts = store.findActiveEmbeddingContracts(request.patchVersion, request.locale)
        if (contracts.isEmpty()) {
            return emptyList()
        }
        if (contracts != setOf(embeddingGateway.contract)) {
            throw RagCorpusContractMismatchException(
                "active patch-note corpus must be reindexed for embedding contract ${embeddingGateway.contract.model}/${embeddingGateway.contract.dimensions}",
            )
        }

        val response = embeddingGateway.embed(listOf(request.query))
        if (response.contract != embeddingGateway.contract || response.vectors.size != 1) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned an invalid query embedding response")
        }
        val queryEmbedding = response.vectors.single()
        if (queryEmbedding.values.size != response.contract.dimensions) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned an unexpected query vector dimension")
        }

        return store
            .search(
                patchVersion = request.patchVersion,
                locale = request.locale,
                contract = embeddingGateway.contract,
                queryEmbedding = queryEmbedding,
                topK = request.topK,
            ).map { row ->
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
            }
    }
}

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
