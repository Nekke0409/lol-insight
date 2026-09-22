package io.github.nekke0409.lolinsight.rag.persistence

import io.github.nekke0409.lolinsight.rag.application.EmbeddingContract
import io.github.nekke0409.lolinsight.rag.application.EmbeddingVector
import io.github.nekke0409.lolinsight.rag.application.IndexedPatchNote
import io.github.nekke0409.lolinsight.rag.application.PatchNoteDocumentStore
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSearchRow
import io.github.nekke0409.lolinsight.rag.application.StoredPatchNoteRevision
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Types
import java.time.ZoneOffset
import java.util.UUID

/**
 * The pgvector operators are deliberately expressed as parameterised SQL: vector ordering stays in PostgreSQL,
 * rather than loading a corpus into JVM memory.
 */
class PgvectorPatchNoteDocumentStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : PatchNoteDocumentStore {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun findRevision(revisionFingerprint: String): StoredPatchNoteRevision? =
        jdbcTemplate
            .query(
                """
                SELECT id, revision_fingerprint, is_active
                FROM rag_patch_note_document_revision
                WHERE revision_fingerprint = :revisionFingerprint
                """.trimIndent(),
                mapOf("revisionFingerprint" to revisionFingerprint),
                revisionMapper,
            ).singleOrNull()

    override fun activateOrStore(indexed: IndexedPatchNote): StoredPatchNoteRevision =
        requireNotNull(
            transactionTemplate.execute {
                val existing = findRevision(indexed.revisionFingerprint)
                if (existing != null) {
                    deactivateCurrentRevision(indexed)
                    jdbcTemplate.update(
                        """
                        UPDATE rag_patch_note_document_revision
                        SET is_active = TRUE
                        WHERE id = :id
                        """.trimIndent(),
                        mapOf("id" to existing.id),
                    )
                    return@execute existing.copy(isActive = true)
                }

                check(indexed.chunks.isNotEmpty()) { "new document revisions require indexed chunks" }
                insertDocumentRevision(indexed)
                insertChunks(indexed)
                deactivateCurrentRevision(indexed)
                jdbcTemplate.update(
                    """
                    UPDATE rag_patch_note_document_revision
                    SET is_active = TRUE
                    WHERE id = :id
                    """.trimIndent(),
                    mapOf("id" to indexed.id),
                )
                StoredPatchNoteRevision(indexed.id, indexed.revisionFingerprint, isActive = true)
            },
        )

    override fun findActiveEmbeddingContracts(
        patchVersion: String,
        locale: String,
    ): Set<EmbeddingContract> =
        jdbcTemplate
            .query(
                """
                SELECT DISTINCT embedding_model, embedding_dimensions
                FROM rag_patch_note_document_revision
                WHERE is_active
                  AND patch_version = :patchVersion
                  AND locale = :locale
                """.trimIndent(),
                mapOf("patchVersion" to patchVersion, "locale" to locale),
            ) { resultSet, _ ->
                EmbeddingContract(resultSet.getString("embedding_model"), resultSet.getInt("embedding_dimensions"))
            }.toSet()

    override fun search(
        patchVersion: String,
        locale: String,
        contract: EmbeddingContract,
        queryEmbedding: EmbeddingVector,
        topK: Int,
    ): List<PatchNoteSearchRow> {
        require(queryEmbedding.values.size == contract.dimensions) { "query vector dimension does not match contract" }
        return jdbcTemplate.query(
            """
            SELECT c.id AS chunk_id,
                   d.id AS document_id,
                   d.title,
                   d.source_url,
                   d.patch_version,
                   d.locale,
                   d.revision_fingerprint,
                   c.heading_path,
                   c.body,
                   c.embedding <=> CAST(:queryVector AS vector) AS cosine_distance
            FROM rag_patch_note_chunk c
            JOIN rag_patch_note_document_revision d ON d.id = c.document_revision_id
            WHERE d.is_active
              AND d.patch_version = :patchVersion
              AND d.locale = :locale
              AND d.embedding_model = :embeddingModel
              AND d.embedding_dimensions = :embeddingDimensions
            ORDER BY c.embedding <=> CAST(:queryVector AS vector), c.id
            LIMIT :topK
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("patchVersion", patchVersion)
                .addValue("locale", locale)
                .addValue("embeddingModel", contract.model)
                .addValue("embeddingDimensions", contract.dimensions)
                .addValue("queryVector", queryEmbedding.toPgvectorLiteral())
                .addValue("topK", topK),
            searchRowMapper,
        )
    }

    private fun insertDocumentRevision(indexed: IndexedPatchNote) {
        jdbcTemplate.update(
            """
            INSERT INTO rag_patch_note_document_revision (
                id, revision_fingerprint, source_url, title, patch_version, locale, published_at,
                snapshot_collected_at, content_hash, parser_version, chunker_version, embedding_model,
                embedding_dimensions, is_active, created_at
            ) VALUES (
                :id, :revisionFingerprint, :sourceUrl, :title, :patchVersion, :locale, :publishedAt,
                :snapshotCollectedAt, :contentHash, :parserVersion, :chunkerVersion, :embeddingModel,
                :embeddingDimensions, FALSE, :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", indexed.id)
                .addValue("revisionFingerprint", indexed.revisionFingerprint)
                .addValue("sourceUrl", indexed.snapshot.sourceUrl)
                .addValue("title", indexed.snapshot.title)
                .addValue("patchVersion", indexed.snapshot.patchVersion)
                .addValue("locale", indexed.snapshot.locale)
                .addValue("publishedAt", indexed.snapshot.publishedAt?.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("snapshotCollectedAt", indexed.snapshot.collectedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("contentHash", indexed.contentHash)
                .addValue("parserVersion", indexed.parserVersion)
                .addValue("chunkerVersion", indexed.chunkerVersion)
                .addValue("embeddingModel", indexed.embeddingContract.model)
                .addValue("embeddingDimensions", indexed.embeddingContract.dimensions)
                .addValue("createdAt", indexed.createdAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE),
        )
    }

    private fun insertChunks(indexed: IndexedPatchNote) {
        indexed.chunks.forEach { indexedChunk ->
            jdbcTemplate.update(
                """
                INSERT INTO rag_patch_note_chunk (
                    id, document_revision_id, chunk_index, heading_path, context_prefix, body,
                    embedding_input_hash, embedding
                ) VALUES (
                    :id, :documentRevisionId, :chunkIndex, :headingPath, :contextPrefix, :body,
                    :embeddingInputHash, CAST(:embedding AS vector)
                )
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", indexedChunk.id)
                    .addValue("documentRevisionId", indexed.id)
                    .addValue("chunkIndex", indexedChunk.chunk.index)
                    .addValue("headingPath", indexedChunk.chunk.headingPath.toTypedArray(), Types.ARRAY)
                    .addValue("contextPrefix", indexedChunk.chunk.contextPrefix)
                    .addValue("body", indexedChunk.chunk.body)
                    .addValue("embeddingInputHash", indexedChunk.embeddingInputHash)
                    .addValue("embedding", indexedChunk.embedding.toPgvectorLiteral()),
            )
        }
    }

    private fun deactivateCurrentRevision(indexed: IndexedPatchNote) {
        jdbcTemplate.update(
            """
            UPDATE rag_patch_note_document_revision
            SET is_active = FALSE
            WHERE source_url = :sourceUrl
              AND patch_version = :patchVersion
              AND locale = :locale
              AND is_active
            """.trimIndent(),
            mapOf(
                "sourceUrl" to indexed.snapshot.sourceUrl,
                "patchVersion" to indexed.snapshot.patchVersion,
                "locale" to indexed.snapshot.locale,
            ),
        )
    }

    private fun EmbeddingVector.toPgvectorLiteral(): String = values.joinToString(prefix = "[", postfix = "]") { it.toString() }

    private val revisionMapper =
        RowMapper { resultSet: ResultSet, _: Int ->
            StoredPatchNoteRevision(
                id = resultSet.getObject("id", UUID::class.java),
                revisionFingerprint = resultSet.getString("revision_fingerprint"),
                isActive = resultSet.getBoolean("is_active"),
            )
        }

    private val searchRowMapper =
        RowMapper { resultSet: ResultSet, _: Int ->
            PatchNoteSearchRow(
                chunkId = resultSet.getObject("chunk_id", UUID::class.java),
                documentId = resultSet.getObject("document_id", UUID::class.java),
                title = resultSet.getString("title"),
                sourceUrl = resultSet.getString("source_url"),
                patchVersion = resultSet.getString("patch_version"),
                locale = resultSet.getString("locale"),
                revisionFingerprint = resultSet.getString("revision_fingerprint"),
                headingPath = resultSet.getArray("heading_path").array.let { array -> (array as Array<*>).map { it.toString() } },
                body = resultSet.getString("body"),
                cosineDistance = resultSet.getDouble("cosine_distance"),
            )
        }
}
