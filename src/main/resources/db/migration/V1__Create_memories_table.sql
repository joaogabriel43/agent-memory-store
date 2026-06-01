-- ============================================================
-- V1: Enable pgvector extension and create memories table
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memories (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(255) NOT NULL,
    content           TEXT         NOT NULL,
    embedding         VECTOR(1536),
    memory_type       VARCHAR(50)  NOT NULL CHECK (memory_type IN ('EPISODIC', 'SEMANTIC', 'PROCEDURAL')),
    source_memory_ids UUID[]       DEFAULT '{}',
    last_accessed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- HNSW index for cosine similarity search on embeddings
CREATE INDEX idx_memories_embedding ON memories
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- B-tree index for tenant isolation queries
CREATE INDEX idx_memories_tenant_id ON memories (tenant_id);
