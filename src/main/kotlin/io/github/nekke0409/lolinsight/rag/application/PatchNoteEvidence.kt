package io.github.nekke0409.lolinsight.rag.application

import java.util.UUID

/**
 * A request-local patch-note evidence item. Source metadata remains here so callers can build
 * citations without trusting model-generated URLs or identifiers.
 */
data class PatchNoteEvidence(
    val evidenceId: String,
    val chunkId: UUID,
    val documentId: UUID,
    val title: String,
    val sourceUrl: String,
    val patchVersion: String,
    val locale: String,
    val revisionFingerprint: String,
    val headingPath: List<String>,
    val evidenceText: String,
) {
    fun promptCharacters(): Int =
        listOf(
            evidenceId,
            title,
            sourceUrl,
            patchVersion,
            locale,
            revisionFingerprint,
            headingPath.joinToString(" > "),
            evidenceText,
        ).sumOf(String::length)

    fun toCitation() =
        PatchNoteCitation(
            evidenceId,
            sourceUrl,
            title,
            patchVersion,
            locale,
            headingPath,
            chunkId,
            documentId,
            revisionFingerprint,
            evidenceText,
        )
}

data class PatchNoteCitation(
    val evidenceId: String,
    val sourceUrl: String,
    val title: String,
    val patchVersion: String,
    val locale: String,
    val headingPath: List<String>,
    val chunkId: UUID,
    val documentId: UUID,
    val revisionFingerprint: String,
    val evidenceText: String,
)

/** Reusable deterministic selection: only selected evidence is eligible for a later citation. */
object PatchNoteEvidenceSelector {
    fun select(
        results: List<PatchNoteSearchResult>,
        maximumEvidenceCharacters: Int,
        evidenceIdPrefix: String,
    ): List<PatchNoteEvidence> {
        require(maximumEvidenceCharacters > 0) { "maximumEvidenceCharacters must be positive" }
        require(evidenceIdPrefix.isNotBlank()) { "evidenceIdPrefix must not be blank" }

        var remaining = maximumEvidenceCharacters
        val seen = mutableSetOf<UUID>()
        val selected = mutableListOf<PatchNoteEvidence>()
        results.forEach { row ->
            if (!seen.add(row.chunkId)) return@forEach
            val candidate =
                PatchNoteEvidence(
                    "$evidenceIdPrefix${selected.size + 1}",
                    row.chunkId,
                    row.documentId,
                    row.title,
                    row.sourceUrl,
                    row.patchVersion,
                    row.locale,
                    row.revisionFingerprint,
                    row.headingPath,
                    row.evidenceText,
                )
            if (candidate.promptCharacters() > remaining) return@forEach
            remaining -= candidate.promptCharacters()
            selected += candidate
        }
        return selected
    }
}
