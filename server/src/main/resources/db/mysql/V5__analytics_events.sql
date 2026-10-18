ALTER TABLE behavior_events ADD COLUMN duration_ms BIGINT;
ALTER TABLE behavior_events ADD COLUMN scene VARCHAR(32);
ALTER TABLE behavior_events ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'CLIENT';
CREATE TABLE event_export_state (
 id INTEGER PRIMARY KEY CHECK (id=1), last_seq BIGINT NOT NULL, source_id VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO event_export_state(id,last_seq,source_id)
 SELECT 1,COALESCE(MAX(seq),0),UUID() FROM behavior_events;
CREATE TABLE event_export_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 after_seq BIGINT NOT NULL, next_seq BIGINT NOT NULL, row_count INTEGER NOT NULL,
 request_id VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
