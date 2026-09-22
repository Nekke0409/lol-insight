CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_patch_note_document_revision (
    id UUID PRIMARY KEY,
    revision_fingerprint CHAR(64) NOT NULL UNIQUE,
    source_url TEXT NOT NULL,
    title TEXT NOT NULL,
    patch_version VARCHAR(64) NOT NULL,
    locale VARCHAR(32) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NULL,
    snapshot_collected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    content_hash CHAR(64) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_dimensions INTEGER NOT NULL CHECK (embedding_dimensions > 0),
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX uk_rag_patch_note_active_source
    ON rag_patch_note_document_revision (source_url, patch_version, locale)
    WHERE is_active;

CREATE INDEX ix_rag_patch_note_active_corpus
    ON rag_patch_note_document_revision (patch_version, locale, embedding_model, embedding_dimensions)
    WHERE is_active;

CREATE TABLE rag_patch_note_chunk (
    id UUID PRIMARY KEY,
    document_revision_id UUID NOT NULL REFERENCES rag_patch_note_document_revision (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    heading_path TEXT[] NOT NULL,
    context_prefix TEXT NOT NULL,
    body TEXT NOT NULL,
    embedding_input_hash CHAR(64) NOT NULL,
    embedding vector NOT NULL,
    CONSTRAINT uk_rag_patch_note_chunk_revision_index UNIQUE (document_revision_id, chunk_index)
);
