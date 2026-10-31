-- Independent, non-public research corpus; never run this in pixelflow_app.
CREATE DATABASE IF NOT EXISTS pixelflow_corpus CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
USE pixelflow_corpus;
CREATE TABLE IF NOT EXISTS corpus_schema_version (
 version INT PRIMARY KEY,
 owner VARCHAR(64) NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO corpus_schema_version VALUES (1, 'pixelflow-research-corpus');
CREATE TABLE IF NOT EXISTS corpus_batches (
 batch_id VARCHAR(64) PRIMARY KEY,
 source_dataset VARCHAR(64) NOT NULL,
 records_sha256 CHAR(64) NOT NULL,
 manifest_json JSON NOT NULL,
 created_at BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS corpus_documents (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 source_record_id CHAR(64) NOT NULL UNIQUE,
 content_hash CHAR(64) NOT NULL UNIQUE,
 source_dataset VARCHAR(64) NOT NULL,
 source_file VARCHAR(255) NOT NULL,
 source_row BIGINT NOT NULL,
 source_url TEXT NOT NULL,
 original_caption TEXT NOT NULL,
 title VARCHAR(120) NOT NULL,
 content VARCHAR(5000) NOT NULL,
 clips_json TEXT NOT NULL CHECK (JSON_VALID(clips_json)),
 has_video BOOLEAN NOT NULL DEFAULT FALSE,
 topics_json JSON NOT NULL,
 rule_version VARCHAR(64) NOT NULL,
 author_id VARCHAR(36) NULL,
 source_create_time BIGINT NULL,
 ingested_at BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'RESEARCH_ONLY' CHECK (status='RESEARCH_ONLY'),
 license VARCHAR(255) NOT NULL,
 terms_url VARCHAR(512) NOT NULL,
 resource_status VARCHAR(16) NOT NULL,
 resource_check_json JSON NOT NULL,
 INDEX corpus_source (source_dataset, id),
 INDEX corpus_resource (resource_status, id),
 FULLTEXT INDEX corpus_text (title, content) WITH PARSER ngram
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS corpus_batch_items (
 batch_id VARCHAR(64) NOT NULL,
 document_id BIGINT NOT NULL,
 PRIMARY KEY (batch_id, document_id),
 FOREIGN KEY (batch_id) REFERENCES corpus_batches(batch_id),
 FOREIGN KEY (document_id) REFERENCES corpus_documents(id)
) ENGINE=InnoDB;
