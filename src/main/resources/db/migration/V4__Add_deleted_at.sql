ALTER TABLE memories ADD COLUMN deleted_at TIMESTAMP NULL DEFAULT NULL;

CREATE INDEX idx_memories_deleted_at ON memories(deleted_at);
