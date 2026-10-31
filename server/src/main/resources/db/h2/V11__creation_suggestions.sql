CREATE TABLE creation_suggestions (
 id VARCHAR(36) PRIMARY KEY,
 user_id VARCHAR(36) NOT NULL,
 source_title VARCHAR(120) NOT NULL,
 source_content VARCHAR(5000) NOT NULL,
 result_json TEXT NOT NULL,
 expires_at BIGINT NOT NULL,
 applied_selection TEXT,
 applied_response TEXT,
 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX creation_suggestions_expiry ON creation_suggestions(expires_at);
