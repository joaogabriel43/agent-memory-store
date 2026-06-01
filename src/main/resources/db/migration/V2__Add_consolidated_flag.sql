ALTER TABLE memories ADD COLUMN consolidated BOOLEAN DEFAULT FALSE;

CREATE INDEX idx_memories_consolidation ON memories(tenant_id, memory_type, created_at, consolidated);
